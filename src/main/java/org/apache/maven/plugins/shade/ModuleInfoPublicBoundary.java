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
 * Controls which embedded package boundaries are retained in a merged module descriptor.
 */
public enum ModuleInfoPublicBoundary {
    /** Retain only the primary artifact's exports and opens. */
    PRIMARY,

    /** Merge the effective exports and opens of embedded explicit and automatic modules. */
    MERGE;

    /**
     * Parses a public boundary mode without regard to case.
     *
     * @param value public boundary mode
     * @return parsed public boundary mode
     * @throws IllegalArgumentException if the value is not a supported mode
     */
    public static ModuleInfoPublicBoundary fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Module info public boundary must not be null.");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown module info public boundary '" + value + "'. Expected one of: primary, merge.");
        }
    }
}
