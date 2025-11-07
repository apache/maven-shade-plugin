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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;

public class DependencyList implements Iterable<Dependency> {

    private final List<Dependency> dependencies;

    public DependencyList(List<Dependency> dependencies) {
        this.dependencies = new ArrayList<>();
        copyDependencies(dependencies);
    }

    public boolean resolveTransitiveDependenciesExclusions(
            MavenSession session,
            MavenProject originalProject,
            ProjectBuilder projectBuilder,
            File reducedPomFile,
            RepositorySystem repositorySystem,
            List<Dependency> finalDependencies,
            Log log) {
        try {

            synchronized (session.getProjectBuildingRequest()) { // Lock critical section to fix MSHADE-467
                ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
                request.setLocalRepository(session.getLocalRepository());
                request.setRemoteRepositories(originalProject.getRemoteArtifactRepositories());

                ProjectBuildingResult shaded = projectBuilder.build(reducedPomFile, request);

                DependenciesExclusionResolver resolver = new DependenciesExclusionResolver(
                        session, originalProject, shaded.getProject(), repositorySystem, log);

                return resolver.resolve(this, finalDependencies);
            }
        } catch (ArtifactDescriptorException | DependencyCollectionException | ProjectBuildingException e) {
            log.error("Failed to resolve exclusions for " + originalProject.getArtifact(), e);
            throw new RuntimeException(e);
        }
    }

    // MSHADE-413: Must not use objects (for example `Model` or `Dependency`) that are "owned
    // by Maven" and being used by other projects/plugins. Modifying those will break the
    // correctness of the build - or cause an endless loop.
    private void copyDependencies(List<Dependency> dependencies) {
        for (Dependency d : dependencies) {
            Dependency cloned = d.clone();
            this.dependencies.add(cloned);
        }
    }

    @Override
    public Iterator<Dependency> iterator() {
        return Collections.unmodifiableList(dependencies).iterator();
    }

    @Override
    public void forEach(Consumer<? super Dependency> action) {
        Iterable.super.forEach(action);
    }

    @Override
    public Spliterator<Dependency> spliterator() {
        return Iterable.super.spliterator();
    }
}
