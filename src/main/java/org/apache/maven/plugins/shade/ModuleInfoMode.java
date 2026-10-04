/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.shade;

import java.util.Locale;

/**
 * Controls how module descriptors are handled while shading.
 */
public enum ModuleInfoMode {
    /** Discard module descriptors, retaining the historical behavior. */
    DISCARD,

    /** Merge module descriptors into the descriptor of the primary artifact. */
    MERGE;

    /**
     * Parses a module descriptor handling mode without regard to case.
     *
     * @param value module descriptor handling mode
     * @return parsed module descriptor handling mode
     * @throws IllegalArgumentException if the value is not a supported mode
     */
    public static ModuleInfoMode fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Module info mode must not be null.");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT).trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown module info mode '" + value + "'. Expected one of: discard, merge.");
        }
    }
}
