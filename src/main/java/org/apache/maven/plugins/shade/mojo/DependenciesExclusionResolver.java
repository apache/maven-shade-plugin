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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;

/**
 * 1 CASE - direct exclusion with promotion
 * If our pom.xml has a dependency, called A (and A is defined to include in artifactSet).
 * And this dependency has defined exclusions (in our pom.xml),
 * So in reduced pom, we need to add exclusions for these transitive dependencies.
 * <p></p>
 * 2 CASE - direct exclusion without promotion
 * If our pom.xml has a dependency called A (and A is NOT defined to include in artifactSet).
 * And this dependency has defined exclusions (in out pom.xml),
 * So in reduced pom, we need to add original exclusions to A dependency
 * <p></p>
 * 3 CASE - transitive exclusion
 * If our pom.xml has a dependency, called A (and A is defined to include in artifactSet).
 * Inside A pom.xml there can be a dependency (B) with exclusions, and we promote this transitive dependency (B),
 * So in reduced pom, if we have B dependency, we need to keep original exclusions for B.
 */
public class DependenciesExclusionResolver {

    private final MavenSession session;
    private final MavenProject originalProject;

    private final MavenProject shadedProject;
    private final RepositorySystem repositorySystem;
    private final Log log;
    private final CollectResult shadedProjectStructure;

    private boolean pomModified;

    public DependenciesExclusionResolver(
            MavenSession session,
            MavenProject originalProject,
            MavenProject shadedProject,
            RepositorySystem repositorySystem,
            Log log)
            throws DependencyCollectionException {
        this.session = session;
        this.originalProject = originalProject;
        this.shadedProject = shadedProject;
        this.repositorySystem = repositorySystem;
        this.log = log;
        this.shadedProjectStructure = getCollectResult();
        this.pomModified = false;
    }

    private CollectResult getCollectResult() throws DependencyCollectionException {
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(RepositoryUtils.toArtifact(shadedProject.getArtifact()));
        collectRequest.setRepositories(shadedProject.getRemoteProjectRepositories());
        collectRequest.setDependencies(shadedProject.getDependencies().stream()
                .map(d -> RepositoryUtils.toDependency(
                        d, session.getRepositorySession().getArtifactTypeRegistry()))
                .collect(Collectors.toList()));
        if (shadedProject.getDependencyManagement() != null) {
            collectRequest.setManagedDependencies(shadedProject.getDependencyManagement().getDependencies().stream()
                    .map(d -> RepositoryUtils.toDependency(
                            d, session.getRepositorySession().getArtifactTypeRegistry()))
                    .collect(Collectors.toList()));
        }

        return repositorySystem.collectDependencies(session.getRepositorySession(), collectRequest);
    }

    public boolean resolve(DependencyList transitiveDependencies, List<Dependency> finalDependencies)
            throws ArtifactDescriptorException, DependencyCollectionException {

        calculateExclusionsBasedOnMissingDependenciesInFinal(transitiveDependencies, finalDependencies);
        addDirectAndTransitiveExclusions(finalDependencies);

        return pomModified;
    }

    private void calculateExclusionsBasedOnMissingDependenciesInFinal(
            DependencyList transitiveDependencies, List<Dependency> finalDependencies) {
        if (shadedProjectStructure.getRoot() == null) {
            return;
        }

        for (DependencyNode n2 : shadedProjectStructure.getRoot().getChildren()) {
            String artifactId2 = ShadeMojo.getId(RepositoryUtils.toArtifact(n2.getArtifact()));

            for (DependencyNode n3 : n2.getChildren()) {
                // stupid m-a Artifact that has no idea what it is: dependency or artifact?
                org.apache.maven.artifact.Artifact artifact3 = RepositoryUtils.toArtifact(n3.getArtifact());
                artifact3.setScope(n3.getDependency().getScope());
                String artifactId3 = ShadeMojo.getId(artifact3);

                // check if it really isn't in the list of original dependencies. Maven
                // prior to 2.0.8 may grab versions from transients instead of
                // from the direct deps in which case they would be marked included
                // instead of OMITTED_FOR_DUPLICATE

                // also, if not promoting the transitives, level 2's would be included
                boolean found = false;
                for (Dependency dep : transitiveDependencies) {
                    if (ShadeMojo.getId(dep).equals(artifactId3)) {
                        found = true;
                        break;
                    }
                }

                // MSHADE-311: do not add exclusion for provided transitive dep
                //       note: MSHADE-31 introduced the exclusion logic for promoteTransitiveDependencies=true,
                //             but as of 3.2.1 promoteTransitiveDependencies has no effect for provided deps,
                //             which makes this fix even possible (see also MSHADE-181)
                if (!found && !"provided".equals(artifact3.getScope())) {
                    log.debug(String.format(
                            "dependency %s (scope %s) not found in transitive dependencies",
                            artifactId3, artifact3.getScope()));
                    for (Dependency dep : finalDependencies) {
                        if (ShadeMojo.getId(dep).equals(artifactId2)) {
                            // MSHADE-413: First check whether the exclusion has already been added,
                            // because it's meaningless to add it more than once. Certain cases
                            // can end up adding the exclusion "forever" and cause an endless loop
                            // rewriting the whole dependency-reduced-pom.xml file.
                            if (!hasExclusion(dep, artifact3)) {
                                log.debug(String.format(
                                        "Adding exclusion for dependency %s (scope %s) " + "to %s (scope %s)",
                                        artifactId3, artifact3.getScope(), ShadeMojo.getId(dep), dep.getScope()));
                                dep.addExclusion(toExclusion(artifact3));
                                pomModified = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void addDirectAndTransitiveExclusions(List<Dependency> finalDependencies)
            throws ArtifactDescriptorException {
        for (Dependency originalDirectDep : originalProject.getDependencies()) {
            List<Exclusion> directExclusions = getDirectExclusions(originalDirectDep);
            log.debug(
                    "Found " + directExclusions.size() + " direct exclusions for " + originalDirectDep.getArtifactId());

            RepositorySystemSession repoSession = session.getRepositorySession();

            ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
            descriptorRequest.setArtifact(toArtifact(originalDirectDep));
            descriptorRequest.setRepositories(originalProject.getRemoteProjectRepositories());

            ArtifactDescriptorResult descriptorResult =
                    repositorySystem.readArtifactDescriptor(repoSession, descriptorRequest);

            List<org.eclipse.aether.graph.Dependency> dependenciesWithExclusions =
                    getDependenciesWithExclusions(descriptorResult);

            for (Dependency dependencyWithMissingExclusions : finalDependencies) {
                // MSHADE-311: do not add exclusion for provided transitive dep
                //       note: MSHADE-31 introduced the exclusion logic for promoteTransitiveDependencies=true,
                //             but as of 3.2.1 promoteTransitiveDependencies has no effect for provided deps,
                //             which makes this fix even possible (see also MSHADE-181)
                if (!dependencyWithMissingExclusions.getScope().equals("provided")) {
                    // move original exclusions from pom.xml to reduced pom.xml
                    if (isEqual(dependencyWithMissingExclusions, originalDirectDep)) {
                        addDirectExclusions(dependencyWithMissingExclusions, directExclusions);
                    }

                    // move exclusions defined inside dependencies
                    // Our pom.xml has dependency A.
                    // Dependency A has defined dependency B with exclusions
                    // If we add B to reduced pom, we need to keep original exclusions for B
                    addMissingTransitiveExclusions(dependencyWithMissingExclusions, dependenciesWithExclusions);
                }
            }
        }
    }

    private void addDirectExclusions(Dependency dependencyWithMissingExclusions, List<Exclusion> directExclusions) {
        for (Exclusion directExclusion : directExclusions) {
            if (!hasExclusion(dependencyWithMissingExclusions, directExclusion)) {
                String msg = String.format(
                        "Adding direct exclusion for %s:%s to dependency %s (scope %s)",
                        directExclusion.getGroupId(),
                        directExclusion.getArtifactId(),
                        dependencyWithMissingExclusions.getArtifactId(),
                        dependencyWithMissingExclusions.getScope());
                log.debug(msg);
                pomModified = true;
                dependencyWithMissingExclusions.addExclusion(clone(directExclusion));
            }
        }
    }

    private List<org.eclipse.aether.graph.Dependency> getDependenciesWithExclusions(
            ArtifactDescriptorResult descriptorResult) {

        List<org.eclipse.aether.graph.Dependency> toCopyList = descriptorResult.getDependencies();

        ArrayList<org.eclipse.aether.graph.Dependency> copy = new ArrayList<>(toCopyList.size());

        for (org.eclipse.aether.graph.Dependency toCopy : toCopyList) {
            if (toCopy.getScope() != null && toCopy.getScope().equals("test")) {
                // test scope dependencies are not included in the resolved descriptor
                continue;
            }
            copy.add(new org.eclipse.aether.graph.Dependency(
                    toCopy.getArtifact(), toCopy.getScope(), toCopy.isOptional(), toCopy.getExclusions()));
        }
        return copy;
    }

    private void addMissingTransitiveExclusions(
            Dependency depWithMissingExclusions, List<org.eclipse.aether.graph.Dependency> dependenciesWithExclusions) {

        for (org.eclipse.aether.graph.Dependency depWithExclusions : dependenciesWithExclusions) {
            if (isEqual(depWithMissingExclusions, depWithExclusions)) {
                List<Exclusion> exclusions = getExclusions(depWithExclusions);
                for (Exclusion exclusion : exclusions) {
                    if (!hasExclusion(depWithMissingExclusions, exclusion)) {
                        String msg = String.format(
                                "Adding exclusion for %s:%s to dependency %s (scope %s)",
                                exclusion.getGroupId(),
                                exclusion.getArtifactId(),
                                depWithMissingExclusions.getArtifactId(),
                                depWithMissingExclusions.getScope());
                        log.debug(msg);
                        pomModified = true;
                        depWithMissingExclusions.addExclusion(clone(exclusion));
                    }
                }
            }
        }
    }

    private List<Exclusion> getExclusions(org.eclipse.aether.graph.Dependency depWithExclusions) {
        if (depWithExclusions.getExclusions() == null
                || depWithExclusions.getExclusions().isEmpty()) {
            return Collections.emptyList();
        }

        List<Exclusion> exclusions =
                new ArrayList<>(depWithExclusions.getExclusions().size());

        log.debug("Found " + depWithExclusions.getExclusions().size() + " transitive exclusions for "
                + depWithExclusions.getArtifact().getArtifactId());

        for (org.eclipse.aether.graph.Exclusion aetherExclusion : depWithExclusions.getExclusions()) {
            exclusions.add(toExclusion(aetherExclusion));
        }

        return exclusions;
    }

    private List<Exclusion> getDirectExclusions(Dependency dependency) {
        for (Dependency originalDependency : originalProject.getDependencies()) {
            if (isEqual(dependency, originalDependency)) {
                return copy(originalDependency.getExclusions());
            }
        }

        return Collections.emptyList();
    }

    private List<Exclusion> copy(List<Exclusion> toCopyList) {
        ArrayList<Exclusion> copy = new ArrayList<>(toCopyList.size());
        for (Exclusion toCopy : toCopyList) {
            Exclusion exclusion = new Exclusion();
            exclusion.setArtifactId(toCopy.getArtifactId());
            exclusion.setGroupId(toCopy.getGroupId());
            copy.add(exclusion);
        }

        return copy;
    }

    private boolean isEqual(Dependency d1, Dependency d2) {
        return d1.getGroupId().equals(d2.getGroupId()) && d1.getArtifactId().equals(d2.getArtifactId());
    }

    private boolean isEqual(Dependency d1, org.eclipse.aether.graph.Dependency d2) {
        return d1.getGroupId().equals(d2.getArtifact().getGroupId())
                && d1.getArtifactId().equals(d2.getArtifact().getArtifactId());
    }

    private boolean isEqual(Exclusion e1, Exclusion e2) {
        return e1.getGroupId().equals(e2.getGroupId()) && e1.getArtifactId().equals(e2.getArtifactId());
    }

    private Artifact toArtifact(Dependency dependency) {
        org.eclipse.aether.graph.Dependency aetherDep = RepositoryUtils.toDependency(
                dependency, session.getRepositorySession().getArtifactTypeRegistry());

        return aetherDep.getArtifact();
    }

    private boolean hasExclusion(Dependency dep, Exclusion exclusion) {
        for (Exclusion existingExclusion : dep.getExclusions()) {
            if (isEqual(existingExclusion, exclusion)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasExclusion(Dependency dep, org.apache.maven.artifact.Artifact exclusionToCheck) {
        for (Exclusion existingExclusion : dep.getExclusions()) {
            if (existingExclusion.getGroupId().equals(exclusionToCheck.getGroupId())
                    && existingExclusion.getArtifactId().equals(exclusionToCheck.getArtifactId())) {
                return true;
            }
        }

        return false;
    }

    private Exclusion toExclusion(org.apache.maven.artifact.Artifact artifact) {
        Exclusion exclusion = new Exclusion();
        exclusion.setArtifactId(artifact.getArtifactId());
        exclusion.setGroupId(artifact.getGroupId());
        return exclusion;
    }

    private Exclusion toExclusion(org.eclipse.aether.graph.Exclusion aetherExclusion) {
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId(aetherExclusion.getGroupId());
        exclusion.setArtifactId(aetherExclusion.getArtifactId());
        return exclusion;
    }

    private Exclusion clone(Exclusion toClone) {
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId(toClone.getGroupId());
        exclusion.setArtifactId(toClone.getArtifactId());
        return exclusion;
    }
}
