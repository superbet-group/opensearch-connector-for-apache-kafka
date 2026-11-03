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
package io.aiven.kafka.connect.opensearch.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.ErrantRecordReporter;
import org.apache.kafka.connect.sink.SinkRecord;

import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.OperationType;

import io.aiven.kafka.connect.opensearch.OpenSearchSinkConnectorConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectorBulkListenerTest {
    private final SinkRecord record = new SinkRecord("test", 0, null, "1", null, Map.of("value", 1), 0);
    private final BulkRequest request = BulkRequest
            .of(b -> b.operations(o -> o.index(i -> i.index("test").id("1").document(record.value()))));

    @ParameterizedTest
    @ValueSource(strings = { "mapper_parsing_exception", "version_conflict_engine_exception", "security_exception" })
    void itemFailureRejectsBatch(String type) {
        final var listener = new ConnectorBulkListener(config(), null);
        assertThrows(ConnectException.class, () -> listener.afterBulk(1, request, List.of(record), failed(type, 400)));
    }

    @Test
    void ignoringMalformedDocumentsStillRejectsVersionConflicts() {
        final var listener = new ConnectorBulkListener(config("behavior.on.malformed.documents", "ignore"), null);
        assertDoesNotThrow(
                () -> listener.afterBulk(1, request, List.of(record), failed("mapper_parsing_exception", 400)));
        assertThrows(ConnectException.class, () -> listener.afterBulk(1, request, List.of(record),
                failed("version_conflict_engine_exception", 409)));
    }

    @Test
    void ignoringVersionConflictsStillRejectsMalformedDocuments() {
        final var listener = new ConnectorBulkListener(config("behavior.on.version.conflict", "ignore"), null);
        assertDoesNotThrow(() -> listener.afterBulk(1, request, List.of(record),
                failed("version_conflict_engine_exception", 409)));
        assertThrows(ConnectException.class,
                () -> listener.afterBulk(1, request, List.of(record), failed("mapper_parsing_exception", 400)));
    }

    @Test
    void missingResponseItemRejectsBatch() {
        final var listener = new ConnectorBulkListener(config(), null);
        final var empty = BulkResponse.of(b -> b.took(1).errors(false).items(List.of()));
        assertThrows(ConnectException.class, () -> listener.afterBulk(1, request, List.of(record), empty));
    }

    @Test
    void unsuccessfulStatusWithoutErrorRejectsBatch() {
        final var listener = new ConnectorBulkListener(config(), null);
        final var failed = BulkResponse.of(b -> b.took(1)
                .errors(false)
                .items(i -> i.operationType(OperationType.Index).index("test").id("1").status(500)));
        assertThrows(ConnectException.class, () -> listener.afterBulk(1, request, List.of(record), failed));
    }

    @Test
    void deletingMissingDocumentRemainsSuccessful() {
        final var listener = new ConnectorBulkListener(config(), null);
        final var missing = BulkResponse.of(b -> b.took(1)
                .errors(false)
                .items(i -> i.operationType(OperationType.Delete).index("test").id("1").status(404)));
        assertDoesNotThrow(() -> listener.afterBulk(1, request, List.of(record), missing));
    }

    @Test
    void pendingReportTimesOut() {
        final var pendingReport = new CompletableFuture<Void>();
        final ErrantRecordReporter reporter = (record, error) -> pendingReport;
        final var config = new OpenSearchSinkConnectorConfig(Map.of("connection.url", "http://localhost:9200",
                "behavior.on.malformed.documents", "report", "flush.timeout.ms", "80"));
        final var listener = new ConnectorBulkListener(config, reporter);
        final var failure = assertThrows(ConnectException.class,
                () -> listener.afterBulk(1, request, List.of(record), failed("mapper_parsing_exception", 400)));
        assertInstanceOf(TimeoutException.class, failure.getCause());
    }

    @Test
    void http413PreservesCauseAndExplainsPayloadRejection() {
        final var listener = new ConnectorBulkListener(config(), null);
        final var cause = new IOException("HTTP 413");
        final var failure = assertThrows(ConnectException.class,
                () -> listener.afterBulk(1, request, List.of(record), cause));
        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("413 Request Entity Too Large"));
    }

    private OpenSearchSinkConnectorConfig config() {
        return new OpenSearchSinkConnectorConfig(Map.of("connection.url", "http://localhost:9200"));
    }

    private OpenSearchSinkConnectorConfig config(String setting, String value) {
        return new OpenSearchSinkConnectorConfig(Map.of("connection.url", "http://localhost:9200", setting, value));
    }

    private BulkResponse failed(String type, int status) {
        return BulkResponse.of(b -> b.took(1)
                .errors(true)
                .items(i -> i.operationType(OperationType.Index)
                        .index("test")
                        .id("1")
                        .status(status)
                        .error(e -> e.type(type).reason("rejected"))));
    }
}
