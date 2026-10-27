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
package org.apache.maven.plugins.shade.mojo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test for {@link ShadeMojo#outputTimestampYear(String)}.
 */
class OutputTimestampYearTest {

    @Test
    void readsIso8601() {
        assertEquals("2019", ShadeMojo.outputTimestampYear("2019-06-01T00:00:00Z"));
        assertEquals("2020", ShadeMojo.outputTimestampYear("2019-12-31T23:30:00-02:00"), "the year in UTC");
    }

    @Test
    void readsSecondsSinceTheEpoch() {
        assertEquals("2024", ShadeMojo.outputTimestampYear("1704067200"));
    }

    @Test
    void unsetOrDisabledOrInvalidGivesNoYear() {
        assertNull(ShadeMojo.outputTimestampYear(null));
        assertNull(ShadeMojo.outputTimestampYear(""));
        assertNull(ShadeMojo.outputTimestampYear("1"));
        assertNull(ShadeMojo.outputTimestampYear("not a timestamp"));
    }
}
