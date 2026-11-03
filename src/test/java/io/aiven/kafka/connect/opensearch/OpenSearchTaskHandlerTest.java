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
package io.aiven.kafka.connect.opensearch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.connect.sink.SinkRecord;

import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OpenSearchTaskHandlerTest {
    private final CountDownLatch requestSent = new CountDownLatch(1);
    private final CompletableFuture<BulkResponse> response = new CompletableFuture<>();
    private final ApacheHttpClient5Transport transport = mock(ApacheHttpClient5Transport.class);
    private OpenSearchTaskHandler handler;

    @BeforeEach
    void setup() throws Exception {
        when(transport.jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(transport.performRequest(any(), any(), any())).thenReturn(new BooleanResponse(true));
        when(transport.performRequestAsync(any(), any(), any())).thenAnswer(call -> {
            requestSent.countDown();
            return response;
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        response.complete(success());
        if (handler != null) {
            try {
                handler.close();
            } catch (IllegalStateException expected) {
                // Cleanup must preserve a previously observed bulk failure.
            }
        }
    }

    @Test
    void flushWaitsForHttpBeforeReturning() throws Exception {
        handler = newHandler(1000);
        handler.put(List.of(record()));
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final var barrier = executor.submit(handler::flush);
            assertTrue(requestSent.await(1, TimeUnit.SECONDS),
                    "Flush must reach the HTTP transport before checking it waits");
            assertThrows(TimeoutException.class, () -> barrier.get(100, TimeUnit.MILLISECONDS));
            response.complete(success());
            barrier.get(1, TimeUnit.SECONDS);
        } finally {
            response.complete(success());
            executor.shutdownNow();
        }
    }

    @Test
    void emptyPutDetectsBackgroundFailureBeforeFlush() throws Exception {
        handler = newHandler(1);
        handler.put(List.of(record()));
        assertTrue(requestSent.await(1, TimeUnit.SECONDS), "put must send the HTTP request before it fails");
        final var cause = new OutOfMemoryError("bulk completion failed");

        CompletableFuture.runAsync(() -> response.completeExceptionally(cause)).get(1, TimeUnit.SECONDS);

        assertSame(cause, assertThrows(IllegalStateException.class, () -> handler.put(List.of())).getCause());
        assertSame(cause, assertThrows(IllegalStateException.class, handler::flush).getCause());
    }

    @Test
    void closePreservesBulkFailureAndSuppressesTransportFailure() throws Exception {
        handler = newHandler(1);
        handler.put(List.of(record()));
        assertTrue(requestSent.await(1, TimeUnit.SECONDS));
        final var cause = new OutOfMemoryError("bulk completion failed");
        response.completeExceptionally(cause);
        final var closeFailure = new IOException("transport close failed");
        // The second close belongs to @AfterEach.
        doThrow(closeFailure).doNothing().when(transport).close();

        final var failure = assertThrows(IllegalStateException.class, handler::close);

        assertSame(cause, failure.getCause());
        assertArrayEquals(new Throwable[] { closeFailure }, failure.getSuppressed());
        verify(transport).close();
    }

    private OpenSearchTaskHandler newHandler(int maxBufferedRecords) {
        final var config = new OpenSearchSinkConnectorConfig(
                Map.of("connection.url", "http://localhost:9200", "schema.ignore", "true", "key.ignore", "false",
                        "max.buffered.records", Integer.toString(maxBufferedRecords)));
        // Replace only the external transport boundary; handler, clients and ingester are real.
        final var builder = mock(ApacheHttpClient5TransportBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(transport);
        try (final var construction = mockStatic(ApacheHttpClient5TransportBuilder.class)) {
            construction.when(() -> ApacheHttpClient5TransportBuilder.builder(config.httpHosts())).thenReturn(builder);
            return new OpenSearchTaskHandler(config, null);
        }
    }

    private SinkRecord record() {
        return new SinkRecord("test", 0, null, "1", null, Map.of("value", 1), 0);
    }

    private BulkResponse success() {
        return BulkResponse.of(b -> b.took(1)
                .errors(false)
                .items(i -> i.operationType(OperationType.Index).index("test").id("1").status(201)));
    }
}
