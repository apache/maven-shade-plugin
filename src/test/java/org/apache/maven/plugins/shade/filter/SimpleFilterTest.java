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
package org.apache.maven.plugins.shade.filter;

import java.util.Collections;

import org.apache.maven.plugins.shade.mojo.ArchiveFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Benjamin Bentmann
 */
public class SimpleFilterTest {

    @Test
    public void testIsFiltered() {
        SimpleFilter filter;

        filter = new SimpleFilter(null, null, null);
        assertFalse(filter.isFiltered("a.properties"));
        assertFalse(filter.isFiltered("org/Test.class"));

        filter = new SimpleFilter(null, Collections.<String>emptySet(), Collections.<String>emptySet());
        assertFalse(filter.isFiltered("a.properties"));
        assertFalse(filter.isFiltered("org/Test.class"));

        filter = new SimpleFilter(null, Collections.singleton("org/Test.class"), Collections.<String>emptySet());
        assertTrue(filter.isFiltered("a.properties"));
        assertFalse(filter.isFiltered("org/Test.class"));
        assertTrue(filter.isFiltered("org/Test.properties"));

        filter = new SimpleFilter(null, Collections.<String>emptySet(), Collections.singleton("org/Test.class"));
        assertFalse(filter.isFiltered("a.properties"));
        assertTrue(filter.isFiltered("org/Test.class"));
        assertFalse(filter.isFiltered("org/Test.properties"));

        filter = new SimpleFilter(null, Collections.singleton("**/a.properties"), Collections.<String>emptySet());
        assertFalse(filter.isFiltered("a.properties"));
        assertFalse(filter.isFiltered("org/a.properties"));
        assertFalse(filter.isFiltered("org/maven/a.properties"));
        assertTrue(filter.isFiltered("org/maven/a.class"));

        filter = new SimpleFilter(null, Collections.<String>emptySet(), Collections.singleton("org/*"));
        assertFalse(filter.isFiltered("Test.class"));
        assertTrue(filter.isFiltered("org/Test.class"));
        assertFalse(filter.isFiltered("org/apache/Test.class"));

        filter = new SimpleFilter(null, Collections.<String>emptySet(), Collections.singleton("org/**"));
        assertFalse(filter.isFiltered("Test.class"));
        assertTrue(filter.isFiltered("org/Test.class"));
        assertTrue(filter.isFiltered("org/apache/Test.class"));

        filter = new SimpleFilter(null, Collections.<String>emptySet(), Collections.singleton("org/"));
        assertFalse(filter.isFiltered("Test.class"));
        assertTrue(filter.isFiltered("org/Test.class"));
        assertTrue(filter.isFiltered("org/apache/Test.class"));

        // given defaults shall be excluded and a specific include is given when filtering then only specific file must
        // be included
        final ArchiveFilter archiveFilter = mock(ArchiveFilter.class);
        when(archiveFilter.getIncludes()).thenReturn(Collections.singleton("specific include"));
        when(archiveFilter.getExcludes()).thenReturn(Collections.<String>emptySet());
        when(archiveFilter.getExcludeDefaults()).thenReturn(true);
        filter = new SimpleFilter(null, archiveFilter);
        assertFalse(filter.isFiltered("specific include"));
        assertTrue(filter.isFiltered("some other file matched by default include"));

        // given defaults shall be included and a specific include is given when filtering then all files must be
        // included
        when(archiveFilter.getExcludeDefaults()).thenReturn(false);
        filter = new SimpleFilter(null, archiveFilter);
        assertFalse(filter.isFiltered("specific include"));
        assertFalse(filter.isFiltered("some other file matched by default include"));
    }
}
