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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Benjamin Bentmann
 */
public class ArtifactSelectorTest {

    private ArtifactSelector newSelector(Collection<String> includes, Collection<String> excludes, String groupPrefix) {
        return new ArtifactSelector(includes, excludes, groupPrefix);
    }

    @Test
    public void testIsSelected() {
        ArtifactSelector selector;

        selector = newSelector(null, null, null);
        assertTrue(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));

        selector = newSelector(null, null, "");
        assertTrue(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));

        selector = newSelector(null, null, "gid");
        assertTrue(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));
        assertTrue(selector.isSelected(new ArtifactId("gid.test", "aid", "type", "cls")));
        assertFalse(selector.isSelected(new ArtifactId("id", "aid", "type", "cls")));

        selector = newSelector(Collections.<String>emptySet(), Collections.<String>emptySet(), null);
        assertTrue(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));

        selector = newSelector(Collections.singleton("gid:aid"), Collections.<String>emptySet(), null);
        assertTrue(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));
        assertFalse(selector.isSelected(new ArtifactId("gid", "id", "type", "cls")));

        selector = newSelector(Collections.<String>emptySet(), Collections.singleton("gid:aid"), null);
        assertFalse(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));
        assertTrue(selector.isSelected(new ArtifactId("gid", "id", "type", "cls")));

        selector = newSelector(Collections.singleton("gid:*"), Collections.singleton("*:aid"), null);
        assertTrue(selector.isSelected(new ArtifactId("gid", "id", "type", "cls")));
        assertFalse(selector.isSelected(new ArtifactId("gid", "aid", "type", "cls")));
        assertFalse(selector.isSelected(new ArtifactId("gid.test", "id", "type", "cls")));
    }

    @Test
    public void testUnmatchedPatterns() {
        ArtifactSelector selector = newSelector(
                Arrays.asList("gid:aid", "gid:typo", "other:*"), Arrays.asList("gid:aid:jar:tests", "nope:*"), "gid");

        selector.isSelected(new ArtifactId("gid", "aid", "jar", ""));
        selector.isSelected(new ArtifactId("gid", "aid", "jar", "tests"));
        selector.isSelected(new ArtifactId("other", "lib", "jar", ""));

        assertEquals(Collections.singletonList("gid:typo"), selector.getUnmatchedIncludes());
        assertEquals(Collections.singletonList("nope:*"), selector.getUnmatchedExcludes());
    }

    @Test
    public void testExcludedArtifactStillCountsAsMatchingTheInclude() {
        ArtifactSelector selector = newSelector(Collections.singleton("gid:*"), Collections.singleton("gid:aid"), null);

        assertFalse(selector.isSelected(new ArtifactId("gid", "aid", "jar", "")));

        assertTrue(selector.getUnmatchedIncludes().isEmpty());
        assertTrue(selector.getUnmatchedExcludes().isEmpty());
    }

    @Test
    public void testNothingConfiguredReportsNothing() {
        ArtifactSelector selector = newSelector(null, null, "gid");

        selector.isSelected(new ArtifactId("other", "aid", "jar", ""));

        assertTrue(
                selector.getUnmatchedIncludes().isEmpty(), "the shadedGroupFilter prefix is not a configured include");
        assertTrue(selector.getUnmatchedExcludes().isEmpty());
    }
}
