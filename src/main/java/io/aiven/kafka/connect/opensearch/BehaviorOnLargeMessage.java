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

import java.util.Locale;

import org.apache.kafka.common.config.ConfigDef;

/**
 * How to handle records whose serialized payload exceeds {@code max.batch.payload.bytes}.
 *
 * <ul>
 *   <li>{@link #FAIL} – throw a {@link org.apache.kafka.connect.errors.ConnectException} and stop the task.</li>
 *   <li>{@link #SKIP} – log and silently discard the oversized record.</li>
 *   <li>{@link #PASS} – forward the record anyway; the bulk request may be rejected with HTTP 413.</li>
 * </ul>
 */
public enum BehaviorOnLargeMessage {
    FAIL, SKIP, PASS;

    public static final BehaviorOnLargeMessage DEFAULT = PASS;

    // Want values for "behavior.on.large.message" property to be case-insensitive
    public static final ConfigDef.Validator VALIDATOR = new ConfigDef.Validator() {
        private final ConfigDef.ValidString validator = ConfigDef.ValidString.in(names());

        @Override
        public void ensureValid(final String name, final Object value) {
            if (value instanceof String) {
                final String lowerCaseStringValue = ((String) value).toLowerCase(Locale.ROOT);
                validator.ensureValid(name, lowerCaseStringValue);
            } else {
                validator.ensureValid(name, value);
            }
        }

        // Overridden here so that ConfigDef.toEnrichedRst shows possible values correctly
        @Override
        public String toString() {
            return validator.toString();
        }
    };

    public static String[] names() {
        final BehaviorOnLargeMessage[] behaviors = values();
        final String[] result = new String[behaviors.length];
        for (int i = 0; i < behaviors.length; i++) {
            result[i] = behaviors[i].toString();
        }
        return result;
    }

    public static BehaviorOnLargeMessage forValue(final String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
