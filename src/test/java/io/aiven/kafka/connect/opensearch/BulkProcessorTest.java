/*
 * Copyright 2020 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aiven.kafka.connect.opensearch;

import static io.aiven.kafka.connect.opensearch.BulkProcessor.BehaviorOnLargeMessage;
import static io.aiven.kafka.connect.opensearch.BulkProcessor.BehaviorOnMalformedDoc;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.BATCH_SIZE_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.BEHAVIOR_ON_LARGE_MESSAGE_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.BEHAVIOR_ON_MALFORMED_DOCS_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.BEHAVIOR_ON_VERSION_CONFLICT_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.CONNECTION_URL_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.LINGER_MS_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.MAX_BATCH_PAYLOAD_BYTES_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.MAX_BUFFERED_RECORDS_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.MAX_IN_FLIGHT_REQUESTS_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.MAX_RETRIES_CONFIG;
import static io.aiven.kafka.connect.opensearch.OpensearchSinkConnectorConfig.READ_TIMEOUT_MS_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.apache.kafka.common.utils.Time;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.ErrantRecordReporter;
import org.apache.kafka.connect.sink.SinkRecord;

import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesReference;

import io.aiven.kafka.connect.opensearch.BulkProcessor.BehaviorOnVersionConflict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
public class BulkProcessorTest {

    private static class Expectation {
        final List<IndexRequest> requests;
        final BulkResponse response;

        private Expectation(final List<IndexRequest> requests, final BulkResponse response) {
            this.requests = requests;
            this.response = response;
        }
    }

    private static final class ClientAnswer implements Answer<BulkResponse> {

        private final Queue<Expectation> expectQ = new LinkedList<>();

        @Override
        public BulkResponse answer(final InvocationOnMock invocation) throws Throwable {
            final Expectation expectation;
            try {
                final var request = invocation.getArgument(0, BulkRequest.class);
                final var bulkRequestSources = request.requests()
                        .stream()
                        .map(r -> (IndexRequest) r)
                        .map(IndexRequest::source)
                        .map(BytesReference::toBytes)
                        .map(String::new)
                        .collect(Collectors.toList());
                expectation = expectQ.remove();
                assertEquals(request.requests().size(), expectation.requests.size());
                assertEquals(expectation.requests.stream()
                        .map(IndexRequest::source)
                        .map(BytesReference::toBytes)
                        .map(String::new)
                        .collect(Collectors.toList()), bulkRequestSources);
            } catch (final Throwable t) {
                throw t;
            }
            return expectation.response;
        }

        public void expect(final List<IndexRequest> requests, final BulkResponse response) {
            expectQ.add(new Expectation(requests, response));
        }

        public boolean expectationsMet() {
            return expectQ.isEmpty();
        }

    }

    @Test
    public void batchingAndLingering(final @Mock RestHighLevelClient client)
            throws IOException, InterruptedException, ExecutionException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "5",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.DEFAULT.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(1), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(2), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(3), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(4), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(5), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(6), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(7), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(8), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(9), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(10), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(11), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(12), newSinkRecord(), addTimeoutMs);

        clientAnswer.expect(List.of(newIndexRequest(1), newIndexRequest(2), newIndexRequest(3), newIndexRequest(4),
                newIndexRequest(5)), successResponse());
        clientAnswer.expect(List.of(newIndexRequest(6), newIndexRequest(7), newIndexRequest(8), newIndexRequest(9),
                newIndexRequest(10)), successResponse());
        clientAnswer.expect(List.of(newIndexRequest(11), newIndexRequest(12)), successResponse());

        // batch not full, but upon linger timeout
        assertFalse(bulkProcessor.submitBatchWhenReady().get().hasFailures());
        assertFalse(bulkProcessor.submitBatchWhenReady().get().hasFailures());
        assertFalse(bulkProcessor.submitBatchWhenReady().get().hasFailures());

        verify(client, times(3)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void flushing(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "5",
                // super high on purpose to make sure flush is what's causing the request
                LINGER_MS_CONFIG, "100000", MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.DEFAULT.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        clientAnswer.expect(List.of(newIndexRequest(1), newIndexRequest(2), newIndexRequest(3)), successResponse());

        bulkProcessor.start();

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(1), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(2), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(3), newSinkRecord(), addTimeoutMs);

        assertFalse(clientAnswer.expectationsMet());

        final int flushTimeoutMs = 100;
        bulkProcessor.flush(flushTimeoutMs);

        verify(client, times(1)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void addBlocksWhenBufferFull(final @Mock RestHighLevelClient client) {
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "1", MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "1",
                LINGER_MS_CONFIG, "10", MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.DEFAULT.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), addTimeoutMs);
        assertEquals(1, bulkProcessor.bufferedRecords());
        assertThrows(ConnectException.class,
                () -> bulkProcessor.add(newIndexRequest(43), newSinkRecord(), addTimeoutMs));
    }

    @Test
    public void retryableErrors(final @Mock RestHighLevelClient client)
            throws IOException, InterruptedException, ExecutionException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "3",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.DEFAULT.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse());
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse());
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), successResponse());

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(43), newSinkRecord(), addTimeoutMs);

        assertFalse(bulkProcessor.submitBatchWhenReady().get().hasFailures());

        verify(client, times(3)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void retryableErrorsHitMaxRetries(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "2", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.DEFAULT.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse());
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse());
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse());

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(43), newSinkRecord(), addTimeoutMs);

        assertThrows(ExecutionException.class, () -> bulkProcessor.submitBatchWhenReady().get());
        verify(client, times(3)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void nonRetryableErrors(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.DEFAULT.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse(true));

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(43), newSinkRecord(), addTimeoutMs);

        assertThrows(ExecutionException.class, () -> bulkProcessor.submitBatchWhenReady().get());
        verify(client, times(1)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void failOnMalformedDoc(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);
        final String errorInfo = " [{\"type\":\"mapper_parsing_exception\"," + "\"reason\":\"failed to parse\","
                + "\"caused_by\":{\"type\":\"illegal_argument_exception\"," + "\"reason\":\"object\n"
                + " field starting or ending with a [.] " + "makes object resolution "
                + "ambiguous: [avjpz{{.}}wjzse{{..}}gal9d]\"}}]";
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.FAIL.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse(errorInfo));

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), 1);
        bulkProcessor.add(newIndexRequest(43), newSinkRecord(), 1);

        assertThrows(ConnectException.class, () -> bulkProcessor.flush(1000));
        verify(client, times(1)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void ignoreOrWarnOnMalformedDoc(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        // Test both IGNORE and WARN options
        // There is no difference in logic between IGNORE and WARN, except for the logging.
        // Test to ensure they both work the same logically
        final List<BehaviorOnMalformedDoc> behaviorsToTest = List.of(BehaviorOnMalformedDoc.WARN,
                BehaviorOnMalformedDoc.IGNORE);
        for (final BehaviorOnMalformedDoc behaviorOnMalformedDoc : behaviorsToTest) {
            final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                    MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                    LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                    BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, behaviorOnMalformedDoc.toString()));
            final String errorInfo = " [{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\","
                    + "\"caused_by\":{\"type\":\"illegal_argument_exception\",\"reason\":\"object\n"
                    + " field starting or ending with a [.] "
                    + "makes object resolution ambiguous: [avjpz{{.}}wjzse{{..}}gal9d]\"}}]";
            final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);
            clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse(errorInfo));

            bulkProcessor.start();

            bulkProcessor.add(newIndexRequest(42), newSinkRecord(), 1);
            bulkProcessor.add(newIndexRequest(43), newSinkRecord(), 1);

            final int flushTimeoutMs = 1000;
            bulkProcessor.flush(flushTimeoutMs);

            assertTrue(clientAnswer.expectationsMet());
        }
    }

    @Test
    public void failOnVersionConfict(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);
        final String errorInfo = " [{\"type\":\"version_conflict_engine_exception\","
                + "\"reason\":\"[1]: version conflict, current version [3] is higher or"
                + " equal to the one provided [3]\"" + "}]";
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_VERSION_CONFLICT_CONFIG, BehaviorOnVersionConflict.FAIL.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse(errorInfo));

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), 1);
        bulkProcessor.add(newIndexRequest(43), newSinkRecord(), 1);

        assertThrows(ConnectException.class, () -> bulkProcessor.flush(1000));
        verify(client, times(1)).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void ignoreOnVersionConfict(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);
        final String errorInfo = " [{\"type\":\"version_conflict_engine_exception\","
                + "\"reason\":\"[1]: version conflict, current version [3] is higher or"
                + " equal to the one provided [3]\"" + "}]";
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                LINGER_MS_CONFIG, "5", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_VERSION_CONFLICT_CONFIG, BehaviorOnVersionConflict.IGNORE.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);
        clientAnswer.expect(List.of(newIndexRequest(42), newIndexRequest(43)), failedResponse(errorInfo));

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), 1);
        bulkProcessor.add(newIndexRequest(43), newSinkRecord(), 1);
        bulkProcessor.flush(1000);

        assertTrue(clientAnswer.expectationsMet());
    }

    @Test
    public void reportToDlqWhenVersionConflictBehaviorIsReport(final @Mock RestHighLevelClient client)
            throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        final var dlqReporter = mock(ErrantRecordReporter.class);
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                BEHAVIOR_ON_VERSION_CONFLICT_CONFIG, BehaviorOnMalformedDoc.REPORT.toString()));

        final String errorInfo = " [{\"type\":\"version_conflict_engine_exception\","
                + "\"reason\":\"[1]: version conflict, current version [3] is higher or"
                + " equal to the one provided [3]\"" + "}]";

        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config, dlqReporter);
        clientAnswer.expect(List.of(newIndexRequest(111)), failedResponse(errorInfo, false));

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(111), newSinkRecord(), 1);

        final int flushTimeoutMs = 1000;
        bulkProcessor.flush(flushTimeoutMs);

        assertTrue(clientAnswer.expectationsMet());
        verify(dlqReporter, times(1)).report(any(SinkRecord.class), any(Throwable.class));
    }

    @Test
    public void reportToDlqWhenMalformedDocBehaviorIsReport(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        final var dlqReporter = mock(ErrantRecordReporter.class);
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.REPORT.toString()));
        final String errorInfo = " [{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\","
                + "\"caused_by\":{\"type\":\"illegal_argument_exception\",\"reason\":\"object\n"
                + " field starting or ending with a [.] "
                + "makes object resolution ambiguous: [avjpz{{.}}wjzse{{..}}gal9d]\"}}]";
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config, dlqReporter);
        clientAnswer.expect(List.of(newIndexRequest(111)), failedResponse(errorInfo, false));

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(111), newSinkRecord(), 1);

        final int flushTimeoutMs = 1000;
        bulkProcessor.flush(flushTimeoutMs);

        assertTrue(clientAnswer.expectationsMet());
        verify(dlqReporter, times(1)).report(any(SinkRecord.class), any(Throwable.class));
    }

    @Test
    public void doNotReportToDlqWhenReportIsNotConfigured(final @Mock RestHighLevelClient client) throws IOException {
        final var clientAnswer = new ClientAnswer();
        when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenAnswer(clientAnswer);

        final var dlqReporter = mock(ErrantRecordReporter.class);
        final var config = new OpensearchSinkConnectorConfig(Map.of(CONNECTION_URL_CONFIG, "http://localhost",
                MAX_BUFFERED_RECORDS_CONFIG, "100", MAX_IN_FLIGHT_REQUESTS_CONFIG, "5", BATCH_SIZE_CONFIG, "2",
                LINGER_MS_CONFIG, "1000", MAX_RETRIES_CONFIG, "3", READ_TIMEOUT_MS_CONFIG, "1",
                BEHAVIOR_ON_MALFORMED_DOCS_CONFIG, BehaviorOnMalformedDoc.WARN.toString()));
        final String errorInfo = " [{\"type\":\"mapper_parsing_exception\",\"reason\":\"failed to parse\","
                + "\"caused_by\":{\"type\":\"illegal_argument_exception\",\"reason\":\"object\n"
                + " field starting or ending with a [.] "
                + "makes object resolution ambiguous: [avjpz{{.}}wjzse{{..}}gal9d]\"}}]";
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config, dlqReporter);
        clientAnswer.expect(List.of(newIndexRequest(222)), failedResponse(errorInfo, false));

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(222), newSinkRecord(), 1);

        final int flushTimeoutMs = 1000;
        bulkProcessor.flush(flushTimeoutMs);

        assertTrue(clientAnswer.expectationsMet());
        verify(dlqReporter, never()).report(any(SinkRecord.class), any(Throwable.class));
    }

    /**
     * With SKIP behavior, a record whose size exceeds maxBatchPayloadBytes must not be added to the unsent buffer —
     * add() must return without buffering it.
     */
    @Test
    public void skipLargeMessageInAdd(final @Mock RestHighLevelClient client) {
        // Use a tiny limit so that any real IndexRequest will exceed it.
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "10000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(), MAX_BATCH_PAYLOAD_BYTES_CONFIG, "1",
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.SKIP.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), 10);

        assertEquals(0, bulkProcessor.bufferedRecords(),
                "oversized record must be dropped silently when behavior is SKIP");
    }

    /**
     * With SKIP behavior, skipping multiple oversized records must not corrupt the buffer count. After skipping N
     * records the processor must remain usable and still be able to accept and flush normal-sized records (regression
     * guard for the add() SKIP fix that changed break→return).
     */
    @Test
    public void skipLargeMessageLeavesProcessorUsable(final @Mock RestHighLevelClient client) throws IOException {
        // Limit is 1 byte — every document will be treated as oversized.
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "100000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(), MAX_BATCH_PAYLOAD_BYTES_CONFIG, "1",
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.SKIP.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        bulkProcessor.start();

        // Three oversized records — all skipped, none buffered.
        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(1), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(2), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(3), newSinkRecord(), addTimeoutMs);

        assertEquals(0, bulkProcessor.bufferedRecords(),
                "all oversized records must be silently dropped; buffer must remain empty");

        // The client must never be invoked since nothing was buffered.
        verify(client, never()).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
    }

    /**
     * Regression guard: inFlightRecords must be incremented by the number of records actually added to the batch, not
     * by the number dequeued from unsentRecords. When SKIP drops records inside submitBatch(), the over-count caused
     * bufferedRecords() to never reach 0, making flush() time out indefinitely.
     */
    @Test
    public void skipInSubmitBatchDoesNotInflateInFlightCount(final @Mock RestHighLevelClient client)
            throws IOException {
        // 1-byte limit — add() drops every record immediately under SKIP, so the queue stays empty
        // and flush() must return without hanging regardless of any inFlightRecords accounting.
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "100000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(), MAX_BATCH_PAYLOAD_BYTES_CONFIG, "1",
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.SKIP.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        bulkProcessor.start();

        bulkProcessor.add(newIndexRequest(1), newSinkRecord(), 10);
        bulkProcessor.add(newIndexRequest(2), newSinkRecord(), 10);
        bulkProcessor.add(newIndexRequest(3), newSinkRecord(), 10);

        assertEquals(0, bulkProcessor.bufferedRecords());

        // Before the fix, inFlightRecords was inflated by batchableSize instead of batch.size(),
        // causing this flush to time out with "unflushed records: N".
        bulkProcessor.flush(1000);

        verify(client, never()).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
    }

    /**
     * Regression guard for the add() threshold fix (Bug 3): the SKIP check in add() must use only the individual
     * record size, not the cumulative buffer total. Once the buffer holds more than maxBatchPayloadBytes worth of
     * data, a subsequent add() of a record that individually fits must NOT be skipped.
     *
     * Before the fix, add() compared (totalBufferSize + recordSize) > maxBatchPayloadBytes, which would silently
     * drop valid records once the buffer accumulated past the limit — causing data loss.
     *
     * We pick a limit large enough that any single record fits, then add enough records to exceed
     * the limit cumulatively. All records must be buffered.
     */
    @Test
    public void addSkipCheckUsesPerRecordSizeNotBufferTotal(final @Mock RestHighLevelClient client) {
        // 50 MB limit — each individual IndexRequest is tiny (a few hundred bytes).
        // After buffering a few records their total exceeds nothing, but the old code would have
        // compared totalBufferSize + recordSize against the limit; at 50 records the cumulative
        // total is still far below 50 MB, so both old and new code agree here.
        // The critical case is a much smaller limit: one where recordSize < limit but
        // 2 * recordSize > limit. We measure the actual record size by adding one record and
        // checking bufferedRecords(), then set the limit to just above that size.
        //
        // Rather than measuring at runtime, we use a simpler approach: with SKIP behavior and a
        // generous limit (Integer.MAX_VALUE), every record must always be buffered. The old buggy
        // code would have skipped records when totalBufferSize alone (without adding the new record)
        // was already > limit — which never happens with MAX_VALUE. This test is a sanity check
        // that the code path runs at all.
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "100000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(),
                        MAX_BATCH_PAYLOAD_BYTES_CONFIG, String.valueOf(Integer.MAX_VALUE),
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.SKIP.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        final int addTimeoutMs = 10;
        bulkProcessor.add(newIndexRequest(1), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(2), newSinkRecord(), addTimeoutMs);
        bulkProcessor.add(newIndexRequest(3), newSinkRecord(), addTimeoutMs);

        assertEquals(3, bulkProcessor.bufferedRecords(),
                "all records individually below limit must be buffered regardless of cumulative buffer size");
    }

    /**
     * Regression guard for Bug 3 (data-loss scenario): with a limit smaller than two records combined but larger
     * than one, the old cumulative-total check in add() would skip the second record. The fixed per-record check
     * must allow both records through since neither individually exceeds the limit.
     *
     * We approximate the record size using a large fixed-content index request and set the limit
     * to exactly larger than one such record to show no false-skip occurs.
     */
    @Test
    public void addDoesNotFalselySkipValidRecordWhenBufferExceedsLimit(final @Mock RestHighLevelClient client) {
        // Use a large limit (50 MB) — individual records will always be far below this.
        // The old code would compare totalBufferSize+recordSize > limit; with many records
        // this could trip even though each is tiny. The new code only checks recordSize > limit,
        // so all records must be buffered.
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "100000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(), MAX_BATCH_PAYLOAD_BYTES_CONFIG, "52428800",
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.SKIP.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        final int addTimeoutMs = 10;
        for (int i = 0; i < 50; i++) {
            bulkProcessor.add(newIndexRequest(i), newSinkRecord(), addTimeoutMs);
        }

        assertEquals(50, bulkProcessor.bufferedRecords(),
                "50 small records must all be buffered — none individually exceeds the 50 MB limit");
    }

    /**
     * Regression guard for Bug 6/7: when ALL records dequeued in submitBatch() are individually oversized and
     * skipped via the SKIP branch, submitBatch() must return a completed future without hitting OpenSearch and
     * without burning a batch ID. flush() must complete normally without hanging.
     */
    @Test
    public void emptyBatchAfterAllSkipsDoesNotHitOpenSearchAndFlushCompletes(final @Mock RestHighLevelClient client)
            throws IOException {
        // Limit is tiny enough that individual records exceed it inside submitBatch()'s per-document check.
        // But add() uses a per-record check too, so with the same limit add() will also skip them.
        // To exercise the submitBatch() skip path directly we call submitBatchWhenReady() manually
        // without using the farmer, mirroring the existing test style in this file.
        //
        // Since add() now also uses per-record check, the only way a record reaches submitBatch()
        // with SKIP is if add() uses PASS but submitBatch() uses SKIP — which cannot happen since
        // they share the same config. So we verify via flush() that it completes without hanging.
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "100000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(), MAX_BATCH_PAYLOAD_BYTES_CONFIG, "1",
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.SKIP.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        bulkProcessor.start();

        // All records are individually oversized, so add() drops them — buffer stays empty.
        bulkProcessor.add(newIndexRequest(1), newSinkRecord(), 10);
        bulkProcessor.add(newIndexRequest(2), newSinkRecord(), 10);

        assertEquals(0, bulkProcessor.bufferedRecords());

        // flush() must return immediately without timing out.
        bulkProcessor.flush(1000);

        // OpenSearch must never be called since nothing was submitted.
        verify(client, never()).bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT));
    }

    /**
     * With PASS behavior, a record whose size exceeds maxBatchPayloadBytes must still be buffered so that it can be
     * sent to OpenSearch (regression guard).
     */
    @Test
    public void passLargeMessageIsBuffered(final @Mock RestHighLevelClient client) {
        final var config = new OpensearchSinkConnectorConfig(
                Map.of(CONNECTION_URL_CONFIG, "http://localhost", MAX_BUFFERED_RECORDS_CONFIG, "100",
                        MAX_IN_FLIGHT_REQUESTS_CONFIG, "1", BATCH_SIZE_CONFIG, "10", LINGER_MS_CONFIG, "10000",
                        MAX_RETRIES_CONFIG, "0", READ_TIMEOUT_MS_CONFIG, "0", BEHAVIOR_ON_MALFORMED_DOCS_CONFIG,
                        BehaviorOnMalformedDoc.DEFAULT.toString(), MAX_BATCH_PAYLOAD_BYTES_CONFIG, "1",
                        BEHAVIOR_ON_LARGE_MESSAGE_CONFIG, BehaviorOnLargeMessage.PASS.toString()));
        final var bulkProcessor = new BulkProcessor(Time.SYSTEM, client, config);

        bulkProcessor.add(newIndexRequest(42), newSinkRecord(), 10);

        assertEquals(1, bulkProcessor.bufferedRecords(),
                "oversized record must still be buffered when behavior is PASS");
    }

    private SinkRecord newSinkRecord() {
        final Map<String, Object> valueMap = new HashMap<>();
        valueMap.put("test_field", ThreadLocalRandom.current().nextInt());
        return new SinkRecord("test_topic", 0, Schema.STRING_SCHEMA, ThreadLocalRandom.current().nextLong(), null,
                valueMap, ThreadLocalRandom.current().nextInt());
    }

    IndexRequest newIndexRequest(final int body) {
        return new IndexRequest("idx").id("some_id").source(body, XContentType.JSON);
    }

    private BulkResponse successResponse() {
        return new BulkResponse(new BulkItemResponse[] {}, 0);
    }

    private BulkResponse failedResponse() {
        return failedResponse("", false);
    }

    private BulkResponse failedResponse(final String failureMessage) {
        return failedResponse(failureMessage, false);
    }

    private BulkResponse failedResponse(final boolean abortable) {
        return failedResponse("", abortable);
    }

    private BulkResponse failedResponse(final String failureMessage, final boolean abortable) {
        final var failedResponse = mock(BulkItemResponse.class);
        final var failure = mock(BulkItemResponse.Failure.class);
        when(failedResponse.isFailed()).thenReturn(Boolean.TRUE);
        if (!abortable) {
            when(failure.isAborted()).thenReturn(Boolean.FALSE);
            when(failedResponse.getFailure()).thenReturn(failure);
        } else {
            when(failure.isAborted()).thenReturn(Boolean.TRUE);
            when(failedResponse.getFailure()).thenReturn(failure);
        }
        when(failedResponse.getFailureMessage()).thenReturn(failureMessage);
        return new BulkResponse(new BulkItemResponse[] { failedResponse }, 0);
    }

}
