/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.opensearch.sink;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.groups.SinkWriterMetricGroup;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.function.ThrowingRunnable;

import org.apache.http.HttpHost;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.bulk.BackoffPolicy;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkProcessor;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.rest.RestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.apache.flink.util.ExceptionUtils.firstOrSuppressed;
import static org.apache.flink.util.Preconditions.checkNotNull;

class OpensearchWriter<IN> implements SinkWriter<IN> {

    private static final Logger LOG = LoggerFactory.getLogger(OpensearchWriter.class);

    public static final FailureHandler DEFAULT_FAILURE_HANDLER =
            ex -> {
                throw new FlinkRuntimeException(ex);
            };

    private final OpensearchEmitter<? super IN> emitter;
    private final MailboxExecutor mailboxExecutor;
    private final boolean flushOnCheckpoint;
    private final BulkProcessor bulkProcessor;
    private final RestHighLevelClient client;
    private final RequestIndexer requestIndexer;
    private final Counter numBytesOutCounter;
    private final FailureHandler failureHandler;

    private long pendingActions = 0;
    private boolean checkpointInProgress = false;
    private volatile long lastSendTime = 0;
    private volatile long ackTime = Long.MAX_VALUE;
    private volatile boolean closed = false;

    /**
     * Constructor creating an Opensearch writer.
     *
     * @param hosts the reachable Opensearch cluster nodes
     * @param emitter converting incoming records to Opensearch actions
     * @param flushOnCheckpoint if true all until now received records are flushed after every
     *         checkpoint
     * @param bulkProcessorConfig describing the flushing and failure handling of the used {@link
     *         BulkProcessor}
     * @param networkClientConfig describing properties of the network connection used to connect to
     *         the Opensearch cluster
     * @param metricGroup for the sink writer
     * @param mailboxExecutor Flink's mailbox executor
     * @param restClientFactory Flink's mailbox executor
     */
    OpensearchWriter(
            List<HttpHost> hosts,
            OpensearchEmitter<? super IN> emitter,
            boolean flushOnCheckpoint,
            BulkProcessorConfig bulkProcessorConfig,
            NetworkClientConfig networkClientConfig,
            SinkWriterMetricGroup metricGroup,
            MailboxExecutor mailboxExecutor,
            RestClientFactory restClientFactory,
            FailureHandler failureHandler) {
        this.emitter = checkNotNull(emitter);
        this.flushOnCheckpoint = flushOnCheckpoint;
        this.mailboxExecutor = checkNotNull(mailboxExecutor);

        final RestClientBuilder builder = RestClient.builder(hosts.toArray(new HttpHost[0]));
        checkNotNull(restClientFactory)
                .configureRestClientBuilder(
                        builder, new DefaultRestClientConfig(networkClientConfig));

        this.client = new RestHighLevelClient(builder);
        this.bulkProcessor = createBulkProcessor(bulkProcessorConfig);
        this.requestIndexer = new DefaultRequestIndexer(metricGroup.getNumRecordsSendCounter());
        checkNotNull(metricGroup);
        metricGroup.setCurrentSendTimeGauge(() -> ackTime - lastSendTime);
        this.numBytesOutCounter = metricGroup.getIOMetricGroup().getNumBytesOutCounter();
        try {
            emitter.open();
        } catch (Exception e) {
            throw new FlinkRuntimeException("Failed to open the OpensearchEmitter", e);
        }
        this.failureHandler = failureHandler;
    }

    @Override
    public void write(IN element, Context context) throws IOException, InterruptedException {
        // do not allow new bulk writes until all actions are flushed
        while (checkpointInProgress) {
            mailboxExecutor.yield();
        }
        emitter.emit(element, context, requestIndexer);
    }

    @Override
    public void flush(boolean endOfInput) throws IOException, InterruptedException {
        checkpointInProgress = true;
        while (pendingActions != 0 && (flushOnCheckpoint || endOfInput)) {
            bulkProcessor.flush();
            LOG.info("Waiting for the response of {} pending actions.", pendingActions);
            mailboxExecutor.yield();
        }
        checkpointInProgress = false;
    }

    @VisibleForTesting
    void blockingFlushAllActions() throws InterruptedException {
        while (pendingActions != 0) {
            bulkProcessor.flush();
            LOG.info("Waiting for the response of {} pending actions.", pendingActions);
            mailboxExecutor.yield();
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        emitter.close();
        bulkProcessor.close();
        client.close();
    }

    private BulkProcessor createBulkProcessor(BulkProcessorConfig bulkProcessorConfig) {

        final BulkProcessor.Builder builder =
                BulkProcessor.builder(
                        new BulkRequestConsumerFactory() { // This cannot be inlined as a
                            // lambda because then
                            // deserialization fails
                            @Override
                            public void accept(
                                    BulkRequest bulkRequest,
                                    ActionListener<BulkResponse> bulkResponseActionListener) {
                                client.bulkAsync(
                                        bulkRequest,
                                        RequestOptions.DEFAULT,
                                        bulkResponseActionListener);
                            }
                        },
                        new BulkListener());

        if (bulkProcessorConfig.getBulkFlushMaxActions() != -1) {
            builder.setBulkActions(bulkProcessorConfig.getBulkFlushMaxActions());
        }

        if (bulkProcessorConfig.getBulkFlushMaxMb() != -1) {
            builder.setBulkSize(
                    new ByteSizeValue(bulkProcessorConfig.getBulkFlushMaxMb(), ByteSizeUnit.MB));
        }

        if (bulkProcessorConfig.getBulkFlushInterval() != -1) {
            builder.setFlushInterval(new TimeValue(bulkProcessorConfig.getBulkFlushInterval()));
        }

        BackoffPolicy backoffPolicy;
        final TimeValue backoffDelay =
                new TimeValue(bulkProcessorConfig.getBulkFlushBackOffDelay());
        final int maxRetryCount = bulkProcessorConfig.getBulkFlushBackoffRetries();
        switch (bulkProcessorConfig.getFlushBackoffType()) {
            case CONSTANT:
                backoffPolicy = BackoffPolicy.constantBackoff(backoffDelay, maxRetryCount);
                break;
            case EXPONENTIAL:
                backoffPolicy = BackoffPolicy.exponentialBackoff(backoffDelay, maxRetryCount);
                break;
            case NONE:
                backoffPolicy = BackoffPolicy.noBackoff();
                break;
            default:
                throw new IllegalArgumentException(
                        "Received unknown backoff policy type "
                                + bulkProcessorConfig.getFlushBackoffType());
        }
        builder.setBackoffPolicy(backoffPolicy);
        // This makes flush() blocking
        builder.setConcurrentRequests(0);

        return builder.build();
    }

    private class BulkListener implements BulkProcessor.Listener {

        @Override
        public void beforeBulk(long executionId, BulkRequest request) {
            LOG.info("Sending bulk of {} actions to Opensearch.", request.numberOfActions());
            lastSendTime = System.currentTimeMillis();
            numBytesOutCounter.inc(request.estimatedSizeInBytes());
        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
            ackTime = System.currentTimeMillis();
            enqueueActionInMailbox(
                    () -> extractFailures(request, response), "opensearchSuccessCallback");
        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
            enqueueActionInMailbox(
                    () -> {
                        throw new FlinkRuntimeException("Complete bulk has failed.", failure);
                    },
                    "opensearchErrorCallback");
        }
    }

    private void enqueueActionInMailbox(
            ThrowingRunnable<? extends Exception> action, String actionName) {
        // If the writer is cancelled before the last bulk response (i.e. no flush on checkpoint
        // configured or shutdown without a final
        // checkpoint) the mailbox might already be shutdown, so we should not enqueue any
        // actions.
        if (isClosed()) {
            return;
        }
        mailboxExecutor.execute(action, actionName);
    }

    private void extractFailures(BulkRequest request, BulkResponse response) {
        if (!response.hasFailures()) {
            pendingActions -= request.numberOfActions();
            return;
        }

        Throwable chainedFailures = null;
        for (int i = 0; i < response.getItems().length; i++) {
            final BulkItemResponse itemResponse = response.getItems()[i];
            if (!itemResponse.isFailed()) {
                continue;
            }
            final Throwable failure = itemResponse.getFailure().getCause();
            if (failure == null) {
                continue;
            }
            final RestStatus restStatus = itemResponse.getFailure().getStatus();
            final DocWriteRequest<?> actionRequest = request.requests().get(i);

            chainedFailures =
                    firstOrSuppressed(
                            wrapException(restStatus, failure, actionRequest), chainedFailures);
        }
        if (chainedFailures == null) {
            return;
        }
        failureHandler.onFailure(chainedFailures);
    }

    private static Throwable wrapException(
            RestStatus restStatus, Throwable rootFailure, DocWriteRequest<?> actionRequest) {
        if (restStatus == null) {
            return new FlinkRuntimeException(
                    String.format("Single action %s of bulk request failed.", actionRequest),
                    rootFailure);
        } else {
            return new FlinkRuntimeException(
                    String.format(
                            "Single action %s of bulk request failed with status %s.",
                            actionRequest, restStatus.getStatus()),
                    rootFailure);
        }
    }

    private boolean isClosed() {
        if (closed) {
            LOG.warn("Writer was closed before all records were acknowledged by Opensearch.");
        }
        return closed;
    }

    private class DefaultRequestIndexer implements RequestIndexer {

        private final Counter numRecordsSendCounter;

        public DefaultRequestIndexer(Counter numRecordsSendCounter) {
            this.numRecordsSendCounter = checkNotNull(numRecordsSendCounter);
        }

        @Override
        public void add(DeleteRequest... deleteRequests) {
            for (final DeleteRequest deleteRequest : deleteRequests) {
                numRecordsSendCounter.inc();
                pendingActions++;
                bulkProcessor.add(deleteRequest);
            }
        }

        @Override
        public void add(IndexRequest... indexRequests) {
            for (final IndexRequest indexRequest : indexRequests) {
                numRecordsSendCounter.inc();
                pendingActions++;
                bulkProcessor.add(indexRequest);
            }
        }

        @Override
        public void add(UpdateRequest... updateRequests) {
            for (final UpdateRequest updateRequest : updateRequests) {
                numRecordsSendCounter.inc();
                pendingActions++;
                bulkProcessor.add(updateRequest);
            }
        }
    }
}
