/*
 * Copyright 2024 Aiven Oy
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

/**
 * Superbet-branded entry point for the OpenSearch Sink Connector.
 *
 * <p>
 * This class extends {@link OpenSearchSinkConnector} without adding any logic. Its purpose is to allow Kafka Connect
 * configurations to reference the Superbet-specific class name (e.g.
 * {@code connector.class=io.aiven.kafka.connect.opensearch.OpenSearchSinkSuperbetConnector}) while inheriting all
 * behaviour from the upstream connector, including the Superbet-ported features: {@code max.batch.payload.bytes},
 * {@code behavior.on.large.message}, and HTTP 413 handling.
 */
public class OpenSearchSinkSuperbetConnector extends OpenSearchSinkConnector {
}
