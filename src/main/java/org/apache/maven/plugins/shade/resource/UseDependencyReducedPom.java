package org.apache.maven.plugins.shade.resource;

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

import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manually creates the resource processors needed to remove the original pom.xml and inject
 * the dependency-reduced-pom.xml in its place in the shaded JAR.
 */
public class UseDependencyReducedPom
{
    public static List<ResourceTransformer> createPomReplaceTransformers(
            MavenProject project,
            File dependencyReducedPomLocation
    )
    {
        String pomInFinalJarFilename =
                "META-INF/maven/" + project.getGroupId() + "/" + project.getArtifactId() + "/pom.xml";

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        DontIncludeResourceTransformer removePom = new DontIncludeResourceTransformer();
        removePom.resource = pomInFinalJarFilename;
        resourceTransformers.add( removePom );

        IncludeResourceTransformer insertDependencyReducedPom = new IncludeResourceTransformer();
        insertDependencyReducedPom.file = dependencyReducedPomLocation;
        insertDependencyReducedPom.resource = pomInFinalJarFilename;
        resourceTransformers.add( insertDependencyReducedPom );

        return resourceTransformers;
    }
}
