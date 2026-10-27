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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;

/**
 * @author Benjamin Bentmann
 */
class ArtifactSelector {

    private Collection<ArtifactId> includes;

    private Collection<ArtifactId> excludes;

    /** The patterns as configured, by the id parsed from each; the patterns added here are not in it. */
    private final Map<ArtifactId, String> configuredIncludes = new LinkedHashMap<>();

    private final Map<ArtifactId, String> configuredExcludes = new LinkedHashMap<>();

    private final Set<ArtifactId> matchedPatterns = Collections.newSetFromMap(new IdentityHashMap<>());

    ArtifactSelector(Artifact projectArtifact, ArtifactSet artifactSet, String groupPrefix) {
        this(
                (artifactSet != null) ? artifactSet.getIncludes() : null,
                (artifactSet != null) ? artifactSet.getExcludes() : null,
                groupPrefix);

        if (projectArtifact != null && !this.includes.isEmpty()) {
            this.includes.add(new ArtifactId(projectArtifact));
        }
    }

    ArtifactSelector(Collection<String> includes, Collection<String> excludes, String groupPrefix) {
        this.includes = toIds(includes, configuredIncludes);
        this.excludes = toIds(excludes, configuredExcludes);

        if (groupPrefix != null && groupPrefix.length() > 0) {
            this.includes.add(new ArtifactId(groupPrefix + "*", "*", "*", "*"));
        }
    }

    private static Collection<ArtifactId> toIds(Collection<String> patterns, Map<ArtifactId, String> configured) {
        Collection<ArtifactId> result = new HashSet<>();

        if (patterns != null) {
            for (String pattern : patterns) {
                ArtifactId id = new ArtifactId(pattern);
                result.add(id);
                configured.put(id, pattern);
            }
        }

        return result;
    }

    public boolean isSelected(Artifact artifact) {
        return artifact != null && isSelected(new ArtifactId(artifact));
    }

    boolean isSelected(ArtifactId id) {
        // Both sets are always evaluated, so that every pattern that matches anything is recorded.
        boolean includeMatched = matches(includes, id);
        boolean excluded = matches(excludes, id);
        return (includes.isEmpty() || includeMatched) && !excluded;
    }

    private boolean matches(Collection<ArtifactId> patterns, ArtifactId id) {
        boolean matched = false;
        for (ArtifactId pattern : patterns) {
            if (id.matches(pattern)) {
                matchedPatterns.add(pattern);
                matched = true;
            }
        }
        return matched;
    }

    /**
     * @return the configured include patterns that have not matched any artifact passed to {@link #isSelected}
     */
    List<String> getUnmatchedIncludes() {
        return unmatched(configuredIncludes);
    }

    /**
     * @return the configured exclude patterns that have not matched any artifact passed to {@link #isSelected}
     */
    List<String> getUnmatchedExcludes() {
        return unmatched(configuredExcludes);
    }

    private List<String> unmatched(Map<ArtifactId, String> configured) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<ArtifactId, String> entry : configured.entrySet()) {
            if (!matchedPatterns.contains(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return result;
    }
}
