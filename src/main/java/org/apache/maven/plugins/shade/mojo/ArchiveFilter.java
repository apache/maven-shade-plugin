package org.apache.maven.plugins.shade.mojo;

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

import java.util.Set;

/**
 * @author David Blevins
 */
public class ArchiveFilter
{
    private String artifact;

    private Set<String> includes;

    private Set<String> excludes;

    private boolean excludeDefaults = true;

    public ArchiveFilter()
    {
        // no-op
    }

    public ArchiveFilter( Set<String> includes, Set<String> excludes )
    {
        this.includes = includes;
        this.excludes = excludes;
    }

    public String getArtifact()
    {
        return artifact;
    }

    public Set<String> getIncludes()
    {
        return includes;
    }

    public Set<String> getExcludes()
    {
        return excludes;
    }

    public boolean getExcludeDefaults()
    {
        return excludeDefaults;
    }
}
