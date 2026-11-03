/*
 * Copyright 2026 Aiven Oy
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
package io.aiven.kafka.connect.opensearch.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.ErrantRecordReporter;
import org.apache.kafka.connect.sink.SinkRecord;

import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.transport.BackoffPolicy;
import org.opensearch.client.transport.OpenSearchTransport;

import io.aiven.kafka.connect.opensearch.OpenSearchSinkConnectorConfig;
import io.aiven.kafka.connect.opensearch.request.ConnectorBulkListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

@Timeout(10)
class BulkIngesterTest {
    private final CompletableFuture<BulkResponse> response = new CompletableFuture<>();
    private final OpenSearchAsyncClient client = mock(OpenSearchAsyncClient.class);

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final SinkRecord record = new SinkRecord("test", 0, null, "1", null, Map.of("value", 1), 0);
    private BulkIngester<SinkRecord> ingester;

    @AfterEach
    void cleanup() {
        response.complete(success());
        if (ingester != null) {
            try {
                ingester.close();
            } catch (IllegalStateException expected) {
                // Failed ingesters remain terminal during cleanup.
            }
        }
        executor.shutdownNow();
    }

    @BeforeEach
    void setup() throws Exception {
        final var transport = mock(OpenSearchTransport.class);
        when(transport.jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(client._transport()).thenReturn(transport);
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);
    }

    @Test
    void completionErrorRejectsFurtherAdmissionWithOriginalCause() {
        ingester = BulkIngester.of(b -> b.client(client).waitTimeout(1000));
        ingester.add(operation());
        ingester.flush();
        final var cause = new OutOfMemoryError("response inspection failed");
        final var brokenResponse = mock(BulkResponse.class);
        when(brokenResponse.items()).thenThrow(cause);

        response.complete(brokenResponse);

        assertSame(cause, assertThrows(IllegalStateException.class, () -> ingester.add(operation())).getCause());
    }

    @Test
    void reportMustFinishBeforeFlushCanSucceed() throws Exception {
        final var report = new CompletableFuture<Void>();
        final var reporting = new CountDownLatch(1);
        final ErrantRecordReporter reporter = (record, error) -> {
            reporting.countDown();
            return report;
        };
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(2000)
                .listener(new ConnectorBulkListener(config("behavior.on.malformed.documents", "report"), reporter)));
        try {
            ingester.add(operation(), record);
            ingester.flush();
            response.complete(failed("mapper_parsing_exception", 400));
            assertTrue(reporting.await(1, TimeUnit.SECONDS));
            final var barrier = startAsync(ingester::flushAndAwait);
            assertThrows(TimeoutException.class, () -> barrier.get(100, TimeUnit.MILLISECONDS));

            report.complete(null);

            barrier.get(1, TimeUnit.SECONDS);
        } finally {
            report.complete(null);
        }
    }

    @Test
    void failedReportRejectsFlush() throws Exception {
        final var cause = new IOException("DLQ unavailable");
        final var report = CompletableFuture.<Void>failedFuture(cause);
        final ErrantRecordReporter reporter = (record, error) -> report;
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(1000)
                .listener(new ConnectorBulkListener(config("behavior.on.malformed.documents", "report"), reporter)));
        ingester.add(operation(), record);
        ingester.flush();
        response.complete(failed("mapper_parsing_exception", 400));

        final var failure = assertThrows(IllegalStateException.class, ingester::flushAndAwait);
        final var reportingFailure = assertInstanceOf(ConnectException.class, failure.getCause());
        final var executionFailure = assertInstanceOf(ExecutionException.class, reportingFailure.getCause());
        assertSame(cause, executionFailure.getCause());
    }

    @Test
    void unknownItemErrorRejectsFlushEvenAfterHttpCompletes() {
        ingester = BulkIngester
                .of(b -> b.client(client).waitTimeout(1000).listener(new ConnectorBulkListener(config(), null)));
        ingester.add(operation(), record);
        ingester.flush();
        response.complete(failed("security_exception", 403));

        final var failure = assertThrows(IllegalStateException.class, ingester::flushAndAwait);
        final var itemFailure = assertInstanceOf(ConnectException.class, failure.getCause());
        assertTrue(itemFailure.getMessage().contains("security_exception"));
        assertSame(failure, assertThrows(IllegalStateException.class, ingester::throwIfFailed));
    }

    @Test
    void configuredIgnoreAllowsFlush() {
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(1000)
                .listener(new ConnectorBulkListener(config("behavior.on.version.conflict", "ignore"), null)));
        ingester.add(operation(), record);
        ingester.flush();
        response.complete(failed("version_conflict_engine_exception", 409));

        ingester.flushAndAwait();
    }

    @Test
    void partialRetryPreservesRecordAndStaysInsideFlushBarrier() throws Exception {
        final var retryResponse = new CompletableFuture<BulkResponse>();
        final var retried = new CountDownLatch(1);
        final var reporter = mock(ErrantRecordReporter.class);
        when(reporter.report(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response).thenAnswer(call -> {
            retried.countDown();
            return retryResponse;
        });
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(2000)
                .backoffPolicy(BackoffPolicy.exponentialBackoff(1L, 1))
                .listener(new ConnectorBulkListener(config("behavior.on.malformed.documents", "report"), reporter)));
        try {
            ingester.add(operation("successful"), record("successful", 10));
            ingester.add(operation("retry"), record("retry", 11));
            ingester.flush();
            response.complete(BulkResponse.of(b -> b.took(1)
                    .errors(true)
                    .items(successItem("successful"), failedItem("retry", "es_rejected_execution_exception", 429))));
            final var barrier = startAsync(ingester::flushAndAwait);
            assertTrue(retried.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> barrier.get(100, TimeUnit.MILLISECONDS));

            // REPORT makes the retried context observable: DLQ must receive the rejected record, not its neighbor.
            retryResponse.complete(failed("retry", "mapper_parsing_exception", 400));

            barrier.get(1, TimeUnit.SECONDS);
            final var requests = ArgumentCaptor.forClass(BulkRequest.class);
            verify(client, times(2)).bulk(requests.capture());
            final var repeatedOperations = requests.getAllValues().get(1).operations();
            assertEquals(1, repeatedOperations.size());
            assertEquals("retry", repeatedOperations.get(0).index().id());
            verify(reporter).report(eq(record("retry", 11)), any());
        } finally {
            retryResponse.complete(success());
        }
    }

    @Test
    void identicalJsonErrorsRetryBothRecords() throws Exception {
        final var reporter = mock(ErrantRecordReporter.class);
        when(reporter.report(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response).thenAnswer(call -> {
            final BulkRequest request = call.getArgument(0);
            final var items = request.operations()
                    .stream()
                    .map(operation -> failedItem(operation.index().id(), "mapper_parsing_exception", 400))
                    .collect(Collectors.toList());
            return CompletableFuture.completedFuture(BulkResponse.of(b -> b.took(1).errors(true).items(items)));
        });
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(2000)
                .backoffPolicy(BackoffPolicy.constantBackoff(1L, 1))
                .listener(new ConnectorBulkListener(config("behavior.on.malformed.documents", "report"), reporter)));
        ingester.add(operation("same"), record("same", 10));
        ingester.add(operation("same"), record("same", 11));
        ingester.flush();
        final var item = rateLimitedItemJson("same");

        response.complete(parseResponseItems(item, item));
        ingester.flushAndAwait();

        final var reported = ArgumentCaptor.forClass(SinkRecord.class);
        verify(reporter, times(2)).report(reported.capture(), any());
        assertEquals(Set.of(10L, 11L),
                reported.getAllValues().stream().map(SinkRecord::kafkaOffset).collect(Collectors.toSet()));
    }

    @Test
    void nullJsonItemRejectsResponseBeforePlanningRetries() throws Exception {
        final var backoff = mock(BackoffPolicy.class);
        when(backoff.iterator()).thenReturn(List.of(1000L).iterator());
        ingester = BulkIngester.of(b -> b.client(client).waitTimeout(1000).backoffPolicy(backoff));
        ingester.add(operation());
        ingester.add(operation());
        ingester.flush();
        response.complete(parseResponseItems(rateLimitedItemJson("1"), "null"));

        final var failure = assertThrows(IllegalStateException.class, ingester::flushAndAwait);
        assertInstanceOf(NullPointerException.class, failure.getCause());
        verify(backoff, never()).iterator();
    }

    @Test
    void exhaustedRetryRejectsFlush() throws Exception {
        final var retryResponse = new CompletableFuture<BulkResponse>();
        when(client.bulk(any(BulkRequest.class))).thenReturn(response, retryResponse);
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(1000)
                .backoffPolicy(BackoffPolicy.exponentialBackoff(1L, 1))
                .listener(new ConnectorBulkListener(config(), null)));
        ingester.add(operation(), record);
        ingester.flush();
        response.complete(failed("es_rejected_execution_exception", 429));
        retryResponse.complete(failed("es_rejected_execution_exception", 429));

        final var failure = assertThrows(IllegalStateException.class, ingester::flushAndAwait);
        final var itemFailure = assertInstanceOf(ConnectException.class, failure.getCause());
        assertTrue(itemFailure.getMessage().contains("es_rejected_execution_exception"));
        verify(client, times(2)).bulk(any(BulkRequest.class));
    }

    @Test
    void missingResponseTimesOutAndRemainsTerminal() {
        ingester = BulkIngester.of(b -> b.client(client).waitTimeout(80));
        ingester.add(operation());

        final var failure = assertThrows(IllegalStateException.class, ingester::flushAndAwait);

        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, ingester::throwIfFailed));
        assertSame(failure, assertThrows(IllegalStateException.class, ingester::close));
    }

    @Test
    void closeDoesNotWaitForeverForMissingResponse() {
        ingester = BulkIngester.of(b -> b.client(client).waitTimeout(80));
        ingester.add(operation());

        final var failure = assertThrows(IllegalStateException.class, ingester::close);
        assertInstanceOf(TimeoutException.class, failure.getCause());
    }

    @Test
    void interruptionRejectsFlushAndPreservesInterruptFlag() throws Exception {
        ingester = BulkIngester.of(b -> b.client(client).waitTimeout(2000));
        ingester.add(operation());
        ingester.flush();
        final var started = new CountDownLatch(1);
        final var interrupted = new CompletableFuture<Boolean>();
        final var waiter = new Thread(() -> {
            started.countDown();
            try {
                ingester.flushAndAwait();
                interrupted.complete(false);
            } catch (IllegalStateException e) {
                interrupted.complete(
                        e.getCause() instanceof InterruptedException && Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(interrupted.get(1, TimeUnit.SECONDS));
            waiter.join(1000);
            assertFalse(waiter.isAlive());
        } finally {
            waiter.interrupt();
        }
    }

    @Test
    void backgroundFailureReleasesBlockedAdmissionAndFlush() throws Exception {
        ingester = BulkIngester.of(b -> b.client(client).maxOperations(1).waitTimeout(2000));
        ingester.add(operation());
        final var admission = startAsync(() -> ingester.add(operation()));
        final var barrier = startAsync(ingester::flushAndAwait);
        assertThrows(TimeoutException.class, () -> admission.get(100, TimeUnit.MILLISECONDS));
        final var cause = new OutOfMemoryError("background failure");

        response.completeExceptionally(cause);

        assertSame(cause,
                assertThrows(ExecutionException.class, () -> admission.get(1, TimeUnit.SECONDS)).getCause().getCause());
        assertSame(cause,
                assertThrows(ExecutionException.class, () -> barrier.get(1, TimeUnit.SECONDS)).getCause().getCause());
    }

    @Test
    void rejectedListenerFailsFlushWithSubmissionCause() {
        final var scheduler = mock(ScheduledExecutorService.class);
        final var cause = new RejectedExecutionException("listener executor stopped");
        doThrow(cause).when(scheduler).execute(any(Runnable.class));
        ingester = BulkIngester.of(b -> b.client(client)
                .waitTimeout(1000)
                .scheduler(scheduler)
                .listener(new ConnectorBulkListener(config(), null)));
        ingester.add(operation(), record);
        ingester.flush();

        response.complete(success());

        assertSame(cause, assertThrows(IllegalStateException.class, ingester::flushAndAwait).getCause());
    }

    @Test
    void successfulRequestCannotClearAnotherRequestsFailure() throws Exception {
        final var successfulResponse = new CompletableFuture<BulkResponse>();
        when(client.bulk(any(BulkRequest.class))).thenReturn(response, successfulResponse);
        ingester = BulkIngester.of(b -> b.client(client).maxConcurrentRequests(2).maxOperations(1).waitTimeout(1000));
        ingester.add(operation("failed"));
        ingester.add(operation("successful"));
        final var cause = new IOException("first request failed");

        response.completeExceptionally(cause);
        successfulResponse.complete(success("successful"));

        assertSame(cause, assertThrows(IllegalStateException.class, ingester::flushAndAwait).getCause());
    }

    @ParameterizedTest
    @MethodSource("submissionFailures")
    void synchronousSubmissionFailureRetainsOriginalCause(Throwable cause) throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenThrow(cause);
        ingester = BulkIngester.of(b -> b.client(client).waitTimeout(1000));
        ingester.add(operation());

        assertSame(cause, assertThrows(IllegalStateException.class, ingester::flushAndAwait).getCause());
        assertSame(cause, assertThrows(IllegalStateException.class, ingester::throwIfFailed).getCause());
    }

    private static Stream<Throwable> submissionFailures() {
        // Both Exception and Error must be published before completion machinery is allocated.
        return Stream.of(new IOException("submission failed"), new OutOfMemoryError("submission failed"));
    }

    private Future<?> startAsync(Runnable action) throws Exception {
        final var started = new CountDownLatch(1);
        final var result = executor.submit(() -> {
            started.countDown();
            action.run();
        });
        assertTrue(started.await(1, TimeUnit.SECONDS), "The action must start before checking that it waits");
        return result;
    }

    private OpenSearchSinkConnectorConfig config() {
        return new OpenSearchSinkConnectorConfig(Map.of("connection.url", "http://localhost:9200"));
    }

    private OpenSearchSinkConnectorConfig config(String setting, String value) {
        return new OpenSearchSinkConnectorConfig(Map.of("connection.url", "http://localhost:9200", setting, value));
    }

    private String rateLimitedItemJson(String id) {
        return "{\"index\":{\"_index\":\"test\",\"_id\":\"" + id + "\",\"status\":429,"
                + "\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"busy\"}}}";
    }

    private BulkResponse parseResponseItems(String... itemsJson) {
        final var mapper = new JacksonJsonpMapper();
        try (final var parser = mapper.jsonProvider()
                .createParser(new StringReader(
                        "{\"took\":1,\"errors\":true,\"items\":[" + String.join(",", itemsJson) + "]}"))) {
            return BulkResponse._DESERIALIZER.deserialize(parser, mapper);
        }
    }

    private BulkResponse success() {
        return success("1");
    }

    private BulkResponse success(String id) {
        return BulkResponse.of(b -> b.took(1).errors(false).items(successItem(id)));
    }

    private BulkResponseItem successItem(String id) {
        return BulkResponseItem.of(i -> i.operationType(OperationType.Index).index("test").id(id).status(201));
    }

    private BulkResponse failed(String type, int status) {
        return failed("1", type, status);
    }

    private BulkResponse failed(String id, String type, int status) {
        return BulkResponse.of(b -> b.took(1).errors(true).items(failedItem(id, type, status)));
    }

    private BulkResponseItem failedItem(String id, String type, int status) {
        return BulkResponseItem.of(i -> i.operationType(OperationType.Index)
                .index("test")
                .id(id)
                .status(status)
                .error(e -> e.type(type).reason("rejected")));
    }

    private BulkOperation operation() {
        return operation("1");
    }

    private BulkOperation operation(String id) {
        return BulkOperation.of(b -> b.index(i -> i.index("test").id(id).document(Map.of("value", 1))));
    }

    private SinkRecord record(String id, long offset) {
        return new SinkRecord("test", 0, null, id, null, Map.of("value", 1), offset);
    }
}
