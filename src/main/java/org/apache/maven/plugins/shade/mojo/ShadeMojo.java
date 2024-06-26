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

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.filter.MinijarFilter;
import org.apache.maven.plugins.shade.filter.SimpleFilter;
import org.apache.maven.plugins.shade.pom.PomWriter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import static org.apache.maven.plugins.shade.resource.UseDependencyReducedPom.createPomReplaceTransformers;

/**
 * Mojo that performs shading delegating to the Shader component.
 *
 * @author Jason van Zyl
 * @author Mauro Talevi
 * @author David Blevins
 * @author Hiram Chirino
 */
// CHECKSTYLE_OFF: LineLength
@Mojo(
        name = "shade",
        defaultPhase = LifecyclePhase.PACKAGE,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
// CHECKSTYLE_ON: LineLength
public class ShadeMojo extends AbstractMojo {
    /**
     * The current Maven session.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Artifacts to include/exclude from the final artifact. Artifacts are denoted by composite identifiers of the
     * general form <code>groupId:artifactId:type:classifier</code>. Since version 1.3, the wildcard characters '*' and
     * '?' can be used within the sub parts of those composite identifiers to do pattern matching. For convenience, the
     * syntax <code>groupId</code> is equivalent to <code>groupId:*:*:*</code>, <code>groupId:artifactId</code> is
     * equivalent to <code>groupId:artifactId:*:*</code> and <code>groupId:artifactId:classifier</code> is equivalent to
     * <code>groupId:artifactId:*:classifier</code>. For example:
     *
     * <pre>
     * &lt;artifactSet&gt;
     *   &lt;includes&gt;
     *     &lt;include&gt;org.apache.maven:*&lt;/include&gt;
     *   &lt;/includes&gt;
     *   &lt;excludes&gt;
     *     &lt;exclude&gt;*:maven-core&lt;/exclude&gt;
     *   &lt;/excludes&gt;
     * &lt;/artifactSet&gt;
     * </pre>
     */
    @Parameter
    private ArtifactSet artifactSet;

    /**
     * Packages to be relocated. For example:
     *
     * <pre>
     * &lt;relocations&gt;
     *   &lt;relocation&gt;
     *     &lt;pattern&gt;org.apache&lt;/pattern&gt;
     *     &lt;shadedPattern&gt;hidden.org.apache&lt;/shadedPattern&gt;
     *     &lt;includes&gt;
     *       &lt;include&gt;org.apache.maven.*&lt;/include&gt;
     *     &lt;/includes&gt;
     *     &lt;excludes&gt;
     *       &lt;exclude&gt;org.apache.maven.Public*&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     *   &lt;/relocation&gt;
     * &lt;/relocations&gt;
     * </pre>
     *
     * <em>Note:</em> Support for includes exists only since version 1.4.
     */
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    @Parameter
    private PackageRelocation[] relocations;

    /**
     * Resource transformers to be used. Please see the "Examples" section for more information on available
     * transformers and their configuration.
     */
    @Parameter
    private ResourceTransformer[] transformers;

    /**
     * Archive Filters to be used. Allows you to specify an artifact in the form of a composite identifier as used by
     * {@link #artifactSet} and a set of include/exclude file patterns for filtering which contents of the archive are
     * added to the shaded jar. From a logical perspective, includes are processed before excludes, thus it's possible
     * to use an include to collect a set of files from the archive then use excludes to further reduce the set. By
     * default, all files are included and no files are excluded. If multiple filters apply to an artifact, the
     * intersection of the matched files will be included in the final JAR. For example:
     *
     * <pre>
     * &lt;filters&gt;
     *   &lt;filter&gt;
     *     &lt;artifact&gt;junit:junit&lt;/artifact&gt;
     *     &lt;includes&gt;
     *       &lt;include&gt;org/junit/**&lt;/include&gt;
     *     &lt;/includes&gt;
     *     &lt;excludes&gt;
     *       &lt;exclude&gt;org/junit/experimental/**&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     *   &lt;/filter&gt;
     * &lt;/filters&gt;
     * </pre>
     */
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    @Parameter
    private ArchiveFilter[] filters;

    /**
     * The destination directory for the shaded artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    private File outputDirectory;

    /**
     * The name of the shaded artifactId.
     * <p/>
     * If you like to change the name of the native artifact, you may use the &lt;build>&lt;finalName> setting. If this
     * is set to something different than &lt;build>&lt;finalName>, no file replacement will be performed, even if
     * shadedArtifactAttached is being used.
     */
    @Parameter
    private String finalName;

    /**
     * The name of the shaded artifactId. So you may want to use a different artifactId and keep the standard version.
     * If the original artifactId was "foo" then the final artifact would be something like foo-1.0.jar. So if you
     * change the artifactId you might have something like foo-special-1.0.jar.
     */
    @Parameter(defaultValue = "${project.artifactId}")
    private String shadedArtifactId;

    /**
     * If specified, this will include only artifacts which have groupIds which start with this.
     */
    @Parameter
    private String shadedGroupFilter;

    /**
     * Defines whether the shaded artifact should be attached as classifier to the original artifact. If false, the
     * shaded jar will be the main artifact of the project
     */
    @Parameter
    private boolean shadedArtifactAttached;

    /**
     * Flag whether to generate a simplified POM for the shaded artifact. If set to <code>true</code>, dependencies that
     * have been included into the uber JAR will be removed from the <code>&lt;dependencies&gt;</code> section of the
     * generated POM. The reduced POM will be named <code>dependency-reduced-pom.xml</code> and is stored into the same
     * directory as the shaded artifact. Unless you also specify dependencyReducedPomLocation, the plugin will create a
     * temporary file named <code>dependency-reduced-pom.xml</code> in the project basedir.
     */
    @Parameter(defaultValue = "true")
    private boolean createDependencyReducedPom;

    /**
     * Where to put the dependency reduced pom. Note: setting a value for this parameter with a directory other than
     * ${basedir} will change the value of ${basedir} for all executions that come after the shade execution. This is
     * often not what you want. This is considered an open issue with this plugin.
     *
     * @since 1.7
     */
    @Parameter(defaultValue = "${basedir}/dependency-reduced-pom.xml")
    private File dependencyReducedPomLocation;

    /**
     * Create a dependency-reduced POM in ${basedir}/drp-UNIQUE.pom. This avoids build collisions of parallel builds
     * without moving the dependency-reduced POM to a different directory. The property
     * maven.shade.dependency-reduced-pom is set to the generated filename.
     *
     * @since 1.7.2
     */
    @Parameter(defaultValue = "false")
    private boolean generateUniqueDependencyReducedPom;

    /**
     * Add dependency reduced POM to the JAR instead of the original one provided by the project.
     * If {@code createDependencyReducedPom} is {@code false} this parameter will be ignored.
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "false")
    private boolean useDependencyReducedPomInJar;

    /**
     * When true, dependencies are kept in the pom but with scope 'provided'; when false, the dependency is removed.
     */
    @Parameter
    private boolean keepDependenciesWithProvidedScope;

    /**
     * When true, transitive deps of removed dependencies are promoted to direct dependencies. This should allow the
     * drop in replacement of the removed deps with the new shaded jar and everything should still work.
     */
    @Parameter
    private boolean promoteTransitiveDependencies;

    /**
     * The name of the classifier used in case the shaded artifact is attached.
     */
    @Parameter(defaultValue = "shaded")
    private String shadedClassifierName;

    /**
     * When true, it will attempt to create a sources jar as well
     */
    @Parameter
    private boolean createSourcesJar;

    /**
     * When true, it will attempt to create a test sources jar.
     */
    @Parameter
    private boolean createTestSourcesJar;

    /**
     * When true, it will attempt to shade the contents of Java source files when creating the sources JAR. When false,
     * it will just relocate the Java source files to the shaded paths, but will not modify the actual source file
     * contents.
     * <p>
     * <b>Please note:</b> This feature uses a heuristic search & replace approach which covers many, but definitely not
     * all possible cases of source code shading and its excludes. There is no full Java parser behind this
     * functionality, which would be the only way to get this right for Java language elements. As for matching within
     * Java string constants, this is next to impossible to get 100% right, trying to guess if they are used in
     * reflection or not.
     * <p>
     * Please understand that the source shading feature is not meant as a source code generator anyway, merely as a
     * tool creating reasonably plausible source code when navigating to a relocated library class from an IDE,
     * hopefully displaying source code which makes 95% sense - no more, no less.
     */
    @Parameter(property = "shadeSourcesContent", defaultValue = "false")
    private boolean shadeSourcesContent;

    /**
     * When true, dependencies will be stripped down on the class level to only the transitive hull required for the
     * artifact. See also {@link #entryPoints}, if you wish to further optimize JAR minimization.
     * <p>
     * <em>Note:</em> This feature uses
     * <a href="https://github.com/tcurdt/jdependency">jdependency</a>. Its accuracy therefore depends on
     * jdependency's limitations.
     *
     * @since 1.4
     */
    @Parameter
    private boolean minimizeJar;

    /**
     * Use this option in order to fine-tune {@link #minimizeJar}: By default, all of the target module's classes are
     * kept and used as entry points for JAR minimization. By explicitly limiting the set of entry points, you can
     * further minimize the set of classes kept in the shaded JAR. This affects both classes in the module itself and
     * dependency classes. If {@link #minimizeJar} is inactive, this option has no effect either.
     * <p>
     * <em>Note:</em> This feature requires Java 1.8 or higher due to its use of
     * <a href="https://github.com/tcurdt/jdependency">jdependency</a>. Its accuracy therefore also depends on
     * jdependency's limitations.
     * <p>
     * Configuration example:
     * <pre>{@code
     * <minimizeJar>true</minimizeJar>
     * <entryPoints>
     *   <entryPoint>org.acme.Application</entryPoint>
     *   <entryPoint>org.acme.OtherEntryPoint</entryPoint>
     * </entryPoints>
     * }</pre>
     *
     * @since 3.5.0
     */
    @Parameter
    private Set<String> entryPoints;

    /**
     * The path to the output file for the shaded artifact. When this parameter is set, the created archive will neither
     * replace the project's main artifact nor will it be attached. Hence, this parameter causes the parameters
     * {@link #finalName}, {@link #shadedArtifactAttached}, {@link #shadedClassifierName} and
     * {@link #createDependencyReducedPom} to be ignored when used.
     *
     * @since 1.3
     */
    @Parameter
    private File outputFile;

    /**
     * You can pass here the roleHint about your own Shader implementation plexus component.
     *
     * @since 1.6
     */
    @Parameter
    private String shaderHint;

    /**
     * When true, the version of each dependency of the reduced pom will be based on the baseVersion of the original
     * dependency instead of its resolved version. For example, if the original pom (transitively) depends on
     * a:a:2.7-SNAPSHOT, if useBaseVersion is set to false, the reduced pom will depend on a:a:2.7-20130312.222222-12
     * whereas if useBaseVersion is set to true, the reduced pom will depend on a:a:2.7-SNAPSHOT
     *
     * @since 3.0
     */
    @Parameter(defaultValue = "false")
    private boolean useBaseVersion;

    /**
     * When true, creates a shaded test-jar artifact as well.
     */
    @Parameter(defaultValue = "false")
    private boolean shadeTestJar;

    /**
     * When true, skips the execution of this MOJO.
     * @since 3.3.0
     */
    @Parameter(defaultValue = "false")
    private boolean skip;

    /**
     * Extra JAR files to infuse into shaded result. Accepts list of files that must exists. If any of specified
     * files does not exist (or is not a file), Mojo will fail.
     * <p>
     * Extra JARs will be processed in same way as main JAR (if any) is: applied relocation, resource transformers
     * but <em>not filtering</em>.
     * <p>
     * Note: this feature should be used lightly, is not meant as ability to replace dependency hull! It is more
     * just a feature to be able to slightly "differentiate" shaded JAR from main only.
     *
     * @since 3.6.0
     */
    @Parameter
    private List<File> extraJars;

    /**
     * Extra Artifacts to infuse into shaded result. Accepts list of GAVs in form of
     * {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>} that will be resolved. If any of them
     * cannot be resolved, Mojo will fail.
     * <p>
     * The artifacts will be resolved (not transitively), and will be processed in same way as dependency JARs
     * are (if any): applied relocation, resource transformers and filtering.
     * <p>
     * Note: this feature should be used lightly, is not meant as ability to replace dependency hull! It is more
     * just a feature to be able to slightly "differentiate" shaded JAR from main only.
     *
     * @since 3.6.0
     */
    @Parameter
    private List<String> extraArtifacts;

    @Inject
    private MavenProjectHelper projectHelper;

    @Inject
    private Shader shader;

    @Inject
    private RepositorySystem repositorySystem;

    /**
     * ProjectBuilder, needed to create projects from the artifacts.
     */
    @Inject
    private ProjectBuilder projectBuilder;

    /**
     * All the present Shaders.
     */
    @Inject
    private Map<String, Shader> shaders;

    /**
     * @throws MojoExecutionException in case of an error.
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Shading has been skipped.");
            return;
        }

        setupHintedShader();

        Set<File> artifacts = new LinkedHashSet<>();
        Set<String> artifactIds = new LinkedHashSet<>();
        Set<File> sourceArtifacts = new LinkedHashSet<>();
        Set<File> testArtifacts = new LinkedHashSet<>();
        Set<File> testSourceArtifacts = new LinkedHashSet<>();

        ArtifactSelector artifactSelector = new ArtifactSelector(project.getArtifact(), artifactSet, shadedGroupFilter);

        if (artifactSelector.isSelected(project.getArtifact())
                && !"pom".equals(project.getArtifact().getType())) {
            if (invalidMainArtifact()) {
                createErrorOutput();
                throw new MojoExecutionException(
                        "Failed to create shaded artifact, " + "project main artifact does not exist.");
            }

            artifacts.add(project.getArtifact().getFile());

            if (extraJars != null && !extraJars.isEmpty()) {
                for (File extraJar : extraJars) {
                    if (!Files.isRegularFile(extraJar.toPath())) {
                        throw new MojoExecutionException(
                                "Failed to create shaded artifact: parameter extraJars contains path " + extraJar
                                        + " that is not a file (does not exist or is not a file)");
                    }
                    artifacts.add(extraJar);
                }
            }

            if (createSourcesJar) {
                File file = shadedSourcesArtifactFile();
                if (file.isFile()) {
                    sourceArtifacts.add(file);
                }
            }

            if (shadeTestJar) {
                File file = shadedTestArtifactFile();
                if (file.isFile()) {
                    testArtifacts.add(file);
                }
            }

            if (createTestSourcesJar) {
                File file = shadedTestSourcesArtifactFile();
                if (file.isFile()) {
                    testSourceArtifacts.add(file);
                }
            }
        }

        processArtifactSelectors(
                artifacts, artifactIds, sourceArtifacts, testArtifacts, testSourceArtifacts, artifactSelector);

        File outputJar = (outputFile != null) ? outputFile : shadedArtifactFileWithClassifier();
        File sourcesJar = shadedSourceArtifactFileWithClassifier();
        File testJar = shadedTestArtifactFileWithClassifier();
        File testSourcesJar = shadedTestSourceArtifactFileWithClassifier();

        // Now add our extra resources
        try {
            List<Filter> filters = getFilters();

            List<Relocator> relocators = getRelocators();

            List<ResourceTransformer> resourceTransformers = getResourceTransformers();

            if (createDependencyReducedPom) {
                createDependencyReducedPom(artifactIds);

                if (useDependencyReducedPomInJar) {
                    // In some cases the used implementation of the resourceTransformers is immutable.
                    resourceTransformers = new ArrayList<>(resourceTransformers);
                    resourceTransformers.addAll(createPomReplaceTransformers(project, dependencyReducedPomLocation));
                }
            }

            ShadeRequest shadeRequest =
                    shadeRequest("jar", artifacts, outputJar, filters, relocators, resourceTransformers);

            shader.shade(shadeRequest);

            if (createSourcesJar) {
                ShadeRequest shadeSourcesRequest = createShadeSourcesRequest(
                        "sources-jar", sourceArtifacts, sourcesJar, filters, relocators, resourceTransformers);

                shader.shade(shadeSourcesRequest);
            }

            if (shadeTestJar) {
                ShadeRequest shadeTestRequest =
                        shadeRequest("test-jar", testArtifacts, testJar, filters, relocators, resourceTransformers);

                shader.shade(shadeTestRequest);
            }

            if (createTestSourcesJar) {
                ShadeRequest shadeTestSourcesRequest = createShadeSourcesRequest(
                        "test-sources-jar",
                        testSourceArtifacts,
                        testSourcesJar,
                        filters,
                        relocators,
                        resourceTransformers);

                shader.shade(shadeTestSourcesRequest);
            }

            if (outputFile == null) {
                boolean renamed = false;

                // rename the output file if a specific finalName is set
                // but don't rename if the finalName is the <build><finalName>
                // because this will be handled implicitly later
                if (finalName != null
                        && finalName.length() > 0 //
                        && !finalName.equals(project.getBuild().getFinalName())) {
                    String finalFileName = finalName + "."
                            + project.getArtifact().getArtifactHandler().getExtension();
                    File finalFile = new File(outputDirectory, finalFileName);
                    replaceFile(finalFile, outputJar);
                    outputJar = finalFile;

                    // Also support the sources JAR
                    if (createSourcesJar) {
                        finalFileName = finalName + "-sources.jar";
                        finalFile = new File(outputDirectory, finalFileName);
                        replaceFile(finalFile, sourcesJar);
                        sourcesJar = finalFile;
                    }

                    // Also support the test JAR
                    if (shadeTestJar) {
                        finalFileName = finalName + "-tests.jar";
                        finalFile = new File(outputDirectory, finalFileName);
                        replaceFile(finalFile, testJar);
                        testJar = finalFile;
                    }

                    if (createTestSourcesJar) {
                        finalFileName = finalName + "-test-sources.jar";
                        finalFile = new File(outputDirectory, finalFileName);
                        replaceFile(finalFile, testSourcesJar);
                        testSourcesJar = finalFile;
                    }

                    renamed = true;
                }

                if (shadedArtifactAttached) {
                    getLog().info("Attaching shaded artifact.");
                    projectHelper.attachArtifact(
                            project, project.getArtifact().getType(), shadedClassifierName, outputJar);
                    if (createSourcesJar) {
                        projectHelper.attachArtifact(
                                project, "java-source", shadedClassifierName + "-sources", sourcesJar);
                    }

                    if (shadeTestJar) {
                        projectHelper.attachArtifact(project, "test-jar", shadedClassifierName + "-tests", testJar);
                    }

                    if (createTestSourcesJar) {
                        projectHelper.attachArtifact(
                                project, "java-source", shadedClassifierName + "-test-sources", testSourcesJar);
                    }
                } else if (!renamed) {
                    getLog().info("Replacing original artifact with shaded artifact.");
                    File originalArtifact = project.getArtifact().getFile();
                    if (originalArtifact != null) {
                        replaceFile(originalArtifact, outputJar);

                        if (createSourcesJar) {
                            getLog().info("Replacing original source artifact with shaded source artifact.");
                            File shadedSources = shadedSourcesArtifactFile();

                            replaceFile(shadedSources, sourcesJar);

                            projectHelper.attachArtifact(project, "java-source", "sources", shadedSources);
                        }

                        if (shadeTestJar) {
                            getLog().info("Replacing original test artifact with shaded test artifact.");
                            File shadedTests = shadedTestArtifactFile();

                            replaceFile(shadedTests, testJar);

                            projectHelper.attachArtifact(project, "test-jar", shadedTests);
                        }

                        if (createTestSourcesJar) {
                            getLog().info("Replacing original test source artifact "
                                    + "with shaded test source artifact.");
                            File shadedTestSources = shadedTestSourcesArtifactFile();

                            replaceFile(shadedTestSources, testSourcesJar);

                            projectHelper.attachArtifact(project, "java-source", "test-sources", shadedTestSources);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error creating shaded jar: " + e.getMessage(), e);
        }
    }

    private void createErrorOutput() {
        getLog().error("The project main artifact does not exist. This could have the following");
        getLog().error("reasons:");
        getLog().error("- You have invoked the goal directly from the command line. This is not");
        getLog().error("  supported. Please add the goal to the default lifecycle via an");
        getLog().error("  <execution> element in your POM and use \"mvn package\" to have it run.");
        getLog().error("- You have bound the goal to a lifecycle phase before \"package\". Please");
        getLog().error("  remove this binding from your POM such that the goal will be run in");
        getLog().error("  the proper phase.");
        getLog().error("- You removed the configuration of the maven-jar-plugin that produces the main artifact.");
    }

    private ShadeRequest shadeRequest(
            String shade,
            Set<File> artifacts,
            File outputJar,
            List<Filter> filters,
            List<Relocator> relocators,
            List<ResourceTransformer> resourceTransformers) {
        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(artifacts);
        shadeRequest.setUberJar(outputJar);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(toResourceTransformers(shade, resourceTransformers));
        return shadeRequest;
    }

    private ShadeRequest createShadeSourcesRequest(
            String shade,
            Set<File> testArtifacts,
            File testJar,
            List<Filter> filters,
            List<Relocator> relocators,
            List<ResourceTransformer> resourceTransformers) {
        ShadeRequest shadeSourcesRequest =
                shadeRequest(shade, testArtifacts, testJar, filters, relocators, resourceTransformers);
        shadeSourcesRequest.setShadeSourcesContent(shadeSourcesContent);
        return shadeSourcesRequest;
    }

    private void setupHintedShader() throws MojoExecutionException {
        if (shaderHint != null) {
            shader = shaders.get(shaderHint);

            if (shader == null) {
                throw new MojoExecutionException(
                        "unable to lookup own Shader implementation with hint: '" + shaderHint + "'");
            }
        }
    }

    private void processArtifactSelectors(
            Set<File> artifacts,
            Set<String> artifactIds,
            Set<File> sourceArtifacts,
            Set<File> testArtifacts,
            Set<File> testSourceArtifacts,
            ArtifactSelector artifactSelector)
            throws MojoExecutionException {

        List<String> excludedArtifacts = new ArrayList<>();
        List<String> pomArtifacts = new ArrayList<>();
        List<String> emptySourceArtifacts = new ArrayList<>();
        List<String> emptyTestArtifacts = new ArrayList<>();
        List<String> emptyTestSourceArtifacts = new ArrayList<>();

        ArrayList<Artifact> processedArtifacts = new ArrayList<>();
        if (extraArtifacts != null && !extraArtifacts.isEmpty()) {
            processedArtifacts.addAll(extraArtifacts.stream()
                    .map(org.eclipse.aether.artifact.DefaultArtifact::new)
                    .map(RepositoryUtils::toArtifact)
                    .collect(Collectors.toList()));

            for (Artifact artifact : processedArtifacts) {
                try {
                    org.eclipse.aether.artifact.Artifact resolved =
                            resolveArtifact(RepositoryUtils.toArtifact(artifact));
                    if (resolved.getFile() != null) {
                        artifact.setFile(resolved.getFile());
                    }
                } catch (ArtifactResolutionException e) {
                    throw new MojoExecutionException(
                            "Failed to create shaded artifact: parameter extraArtifacts contains artifact "
                                    + artifact.getId() + " that is not resolvable",
                            e);
                }
            }
        }
        processedArtifacts.addAll(project.getArtifacts());

        for (Artifact artifact : processedArtifacts) {
            if (!artifactSelector.isSelected(artifact)) {
                excludedArtifacts.add(artifact.getId());

                continue;
            }

            if ("pom".equals(artifact.getType())) {
                pomArtifacts.add(artifact.getId());
                continue;
            }

            getLog().info("Including " + artifact.getId() + " in the shaded jar.");

            artifacts.add(artifact.getFile());
            artifactIds.add(getId(artifact));

            if (createSourcesJar) {
                File file = resolveArtifactForClassifier(artifact, "sources");
                if (file != null) {
                    if (file.length() > 0) {
                        sourceArtifacts.add(file);
                    } else {
                        emptySourceArtifacts.add(artifact.getArtifactId());
                    }
                }
            }

            if (shadeTestJar) {
                File file = resolveArtifactForClassifier(artifact, "tests");
                if (file != null) {
                    if (file.length() > 0) {
                        testArtifacts.add(file);
                    } else {
                        emptyTestArtifacts.add(artifact.getId());
                    }
                }
            }

            if (createTestSourcesJar) {
                File file = resolveArtifactForClassifier(artifact, "test-sources");
                if (file != null) {
                    testSourceArtifacts.add(file);
                } else {
                    emptyTestSourceArtifacts.add(artifact.getId());
                }
            }
        }

        for (String artifactId : excludedArtifacts) {
            getLog().info("Excluding " + artifactId + " from the shaded jar.");
        }
        for (String artifactId : pomArtifacts) {
            getLog().info("Skipping pom dependency " + artifactId + " in the shaded jar.");
        }
        for (String artifactId : emptySourceArtifacts) {
            getLog().warn("Skipping empty source jar " + artifactId + ".");
        }
        for (String artifactId : emptyTestArtifacts) {
            getLog().warn("Skipping empty test jar " + artifactId + ".");
        }
        for (String artifactId : emptyTestSourceArtifacts) {
            getLog().warn("Skipping empty test source jar " + artifactId + ".");
        }
    }

    private boolean invalidMainArtifact() {
        return project.getArtifact().getFile() == null
                || !project.getArtifact().getFile().isFile();
    }

    private void replaceFile(File oldFile, File newFile) throws MojoExecutionException {
        getLog().info("Replacing " + oldFile + " with " + newFile);

        File origFile = new File(outputDirectory, "original-" + oldFile.getName());
        if (oldFile.exists() && !oldFile.renameTo(origFile)) {
            // try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if (!oldFile.renameTo(origFile)) {
                // Still didn't work. We'll do a copy
                try {
                    copyFiles(oldFile, origFile);
                } catch (IOException ex) {
                    // kind of ignorable here. We're just trying to save the original
                    getLog().warn(ex);
                }
            }
        }
        if (!newFile.renameTo(oldFile)) {
            // try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if (!newFile.renameTo(oldFile)) {
                // Still didn't work. We'll do a copy
                try {
                    copyFiles(newFile, oldFile);
                } catch (IOException ex) {
                    throw new MojoExecutionException("Could not replace original artifact with shaded artifact!", ex);
                }
            }
        }
    }

    private void copyFiles(File source, File target) throws IOException {
        try (InputStream in = Files.newInputStream(source.toPath());
                OutputStream out = Files.newOutputStream(target.toPath())) {
            IOUtil.copy(in, out);
        }
    }

    private File resolveArtifactForClassifier(Artifact artifact, String classifier) {
        Artifact toResolve = new DefaultArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersionRange() == null
                        ? VersionRange.createFromVersion(artifact.getVersion())
                        : artifact.getVersionRange(),
                artifact.getScope(),
                artifact.getType(),
                classifier,
                artifact.getArtifactHandler(),
                artifact.isOptional());
        try {
            org.eclipse.aether.artifact.Artifact resolved = resolveArtifact(RepositoryUtils.toArtifact(toResolve));
            if (resolved.getFile() != null) {
                return resolved.getFile();
            }
            return null;
        } catch (ArtifactResolutionException e) {
            getLog().warn("Could not get " + classifier + " for " + artifact);
            return null;
        }
    }

    private org.eclipse.aether.artifact.Artifact resolveArtifact(org.eclipse.aether.artifact.Artifact artifact)
            throws ArtifactResolutionException {
        return repositorySystem
                .resolveArtifact(
                        session.getRepositorySession(),
                        new ArtifactRequest(artifact, project.getRemoteProjectRepositories(), "shade"))
                .getArtifact();
    }

    private List<Relocator> getRelocators() {
        List<Relocator> relocators = new ArrayList<>();

        if (relocations == null) {
            return relocators;
        }

        for (PackageRelocation r : relocations) {
            relocators.add(new SimpleRelocator(
                    r.getPattern(), r.getShadedPattern(), r.getIncludes(), r.getExcludes(), r.isRawString()));
        }

        return relocators;
    }

    private List<ResourceTransformer> getResourceTransformers() throws MojoExecutionException {
        if (transformers == null) {
            return Collections.emptyList();
        }
        for (ResourceTransformer transformer : transformers) {
            if (transformer == null) {
                throw new MojoExecutionException(
                        "Failed to create shaded artifact: parameter transformers contains null (double-check XML attribute)");
            }
        }
        return Arrays.asList(transformers);
    }

    private List<Filter> getFilters() throws MojoExecutionException {
        List<Filter> filters = new ArrayList<>();
        List<SimpleFilter> simpleFilters = new ArrayList<>();

        if (this.filters != null && this.filters.length > 0) {
            Map<Artifact, ArtifactId> artifacts = new HashMap<>();

            artifacts.put(project.getArtifact(), new ArtifactId(project.getArtifact()));

            for (Artifact artifact : project.getArtifacts()) {
                artifacts.put(artifact, new ArtifactId(artifact));
            }

            for (ArchiveFilter filter : this.filters) {
                ArtifactId pattern = new ArtifactId(filter.getArtifact());

                Set<File> jars = new HashSet<>();

                for (Map.Entry<Artifact, ArtifactId> entry : artifacts.entrySet()) {
                    if (entry.getValue().matches(pattern)) {
                        Artifact artifact = entry.getKey();

                        jars.add(artifact.getFile());

                        if (createSourcesJar) {
                            File file = resolveArtifactForClassifier(artifact, "sources");
                            if (file != null) {
                                jars.add(file);
                            }
                        }

                        if (shadeTestJar) {
                            File file = resolveArtifactForClassifier(artifact, "tests");
                            if (file != null) {
                                jars.add(file);
                            }
                        }
                    }
                }

                if (jars.isEmpty()) {
                    getLog().info("No artifact matching filter " + filter.getArtifact());

                    continue;
                }

                simpleFilters.add(new SimpleFilter(jars, filter));
            }
        }

        filters.addAll(simpleFilters);

        if (minimizeJar) {
            if (entryPoints == null) {
                entryPoints = new HashSet<>();
            }
            getLog().info("Minimizing jar " + project.getArtifact()
                    + (entryPoints.isEmpty() ? "" : " with entry points"));

            try {
                filters.add(new MinijarFilter(project, getLog(), simpleFilters, entryPoints));
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to analyze class dependencies", e);
            }
        }

        return filters;
    }

    private File shadedArtifactFileWithClassifier() {
        Artifact artifact = project.getArtifact();
        final String shadedName = shadedArtifactId + "-" + artifact.getVersion() + "-" + shadedClassifierName + "."
                + artifact.getArtifactHandler().getExtension();
        return new File(outputDirectory, shadedName);
    }

    private File shadedSourceArtifactFileWithClassifier() {
        return shadedArtifactFileWithClassifier("sources");
    }

    private File shadedTestSourceArtifactFileWithClassifier() {
        return shadedArtifactFileWithClassifier("test-sources");
    }

    private File shadedArtifactFileWithClassifier(String classifier) {
        Artifact artifact = project.getArtifact();
        final String shadedName = shadedArtifactId + "-" + artifact.getVersion() + "-" + shadedClassifierName + "-"
                + classifier + "." + artifact.getArtifactHandler().getExtension();
        return new File(outputDirectory, shadedName);
    }

    private File shadedTestArtifactFileWithClassifier() {
        return shadedArtifactFileWithClassifier("tests");
    }

    private File shadedSourcesArtifactFile() {
        return shadedArtifactFile("sources");
    }

    private File shadedTestSourcesArtifactFile() {
        return shadedArtifactFile("test-sources");
    }

    private File shadedArtifactFile(String classifier) {
        Artifact artifact = project.getArtifact();

        String shadedName;

        if (project.getBuild().getFinalName() != null) {
            shadedName = project.getBuild().getFinalName() + "-" + classifier + "."
                    + artifact.getArtifactHandler().getExtension();
        } else {
            shadedName = shadedArtifactId + "-" + artifact.getVersion() + "-" + classifier + "."
                    + artifact.getArtifactHandler().getExtension();
        }

        return new File(outputDirectory, shadedName);
    }

    private File shadedTestArtifactFile() {
        return shadedArtifactFile("tests");
    }

    // We need to find the direct dependencies that have been included in the uber JAR so that we can modify the
    // POM accordingly.
    private void createDependencyReducedPom(Set<String> artifactsToRemove)
            throws IOException, ProjectBuildingException, DependencyCollectionException {
        List<Dependency> transitiveDeps = new ArrayList<>();

        // NOTE: By using the getArtifacts() we get the completely evaluated artifacts
        // including the system scoped artifacts with expanded values of properties used.
        for (Artifact artifact : project.getArtifacts()) {
            if ("pom".equals(artifact.getType())) {
                // don't include pom type dependencies in dependency reduced pom
                continue;
            }

            // promote
            Dependency dep = createDependency(artifact);

            // we'll figure out the exclusions in a bit.
            transitiveDeps.add(dep);
        }

        Model model = project.getOriginalModel();

        // MSHADE-413: Must not use objects (for example `Model` or `Dependency`) that are "owned
        // by Maven" and being used by other projects/plugins. Modifying those will break the
        // correctness of the build - or cause an endless loop.
        List<Dependency> origDeps = new ArrayList<>();
        List<Dependency> source = promoteTransitiveDependencies ? transitiveDeps : project.getDependencies();
        for (Dependency d : source) {
            origDeps.add(d.clone());
        }
        model = model.clone();

        // MSHADE-185: We will remove all system scoped dependencies which usually
        // have some kind of property usage. At this time the properties within
        // such things are already evaluated.
        List<Dependency> originalDependencies = model.getDependencies();
        removeSystemScopedDependencies(artifactsToRemove, originalDependencies);

        List<Dependency> dependencies = new ArrayList<>();
        boolean modified = false;
        for (Dependency d : origDeps) {
            if (artifactsToRemove.contains(getId(d))) {
                if (keepDependenciesWithProvidedScope) {
                    if (!"provided".equals(d.getScope())) {
                        modified = true;
                        d.setScope("provided");
                    }
                } else {
                    modified = true;
                    continue;
                }
            }

            dependencies.add(d);
        }

        // MSHADE-155
        model.setArtifactId(shadedArtifactId);

        // MSHADE-185: We will add those system scoped dependencies
        // from the non interpolated original pom file. So we keep
        // things like this: <systemPath>${tools.jar}</systemPath> intact.
        addSystemScopedDependencyFromNonInterpolatedPom(dependencies, originalDependencies);

        // Check to see if we have a reduction and if so rewrite the POM.
        rewriteDependencyReducedPomIfWeHaveReduction(dependencies, modified, transitiveDeps, model);
    }

    private void rewriteDependencyReducedPomIfWeHaveReduction(
            List<Dependency> dependencies, boolean modified, List<Dependency> transitiveDeps, Model model)
            throws IOException, ProjectBuildingException, DependencyCollectionException {
        if (modified) {
            for (int loopCounter = 0; modified; loopCounter++) {

                model.setDependencies(dependencies);

                if (generateUniqueDependencyReducedPom) {
                    dependencyReducedPomLocation = Files.createTempFile(
                                    project.getBasedir().toPath(), "dependency-reduced-pom-", ".xml")
                            .toFile();
                    project.getProperties()
                            .setProperty(
                                    "maven.shade.dependency-reduced-pom",
                                    dependencyReducedPomLocation.getAbsolutePath());
                } else {
                    if (dependencyReducedPomLocation == null) {
                        // MSHADE-123: We can't default to 'target' because it messes up uses of ${project.basedir}
                        dependencyReducedPomLocation = new File(project.getBasedir(), "dependency-reduced-pom.xml");
                    }
                }

                File f = dependencyReducedPomLocation;
                // MSHADE-225
                // Works for now, maybe there's a better algorithm where no for-loop is required
                if (loopCounter == 0) {
                    getLog().info("Dependency-reduced POM written at: " + f.getAbsolutePath());
                }

                if (f.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }

                Writer w = WriterFactory.newXmlWriter(f);

                String replaceRelativePath = null;
                if (model.getParent() != null) {
                    replaceRelativePath = model.getParent().getRelativePath();
                }

                if (model.getParent() != null) {
                    File parentFile =
                            new File(project.getBasedir(), model.getParent().getRelativePath()).getCanonicalFile();
                    if (!parentFile.isFile()) {
                        parentFile = new File(parentFile, "pom.xml");
                    }

                    parentFile = parentFile.getCanonicalFile();

                    String relPath = RelativizePath.convertToRelativePath(parentFile, f);
                    model.getParent().setRelativePath(relPath);
                }

                try {
                    PomWriter.write(w, model, true);
                } finally {
                    if (model.getParent() != null) {
                        model.getParent().setRelativePath(replaceRelativePath);
                    }
                    w.close();
                }

                synchronized (session.getProjectBuildingRequest()) { // Lock critical section to fix MSHADE-467
                    ProjectBuildingRequest projectBuildingRequest =
                            new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
                    projectBuildingRequest.setLocalRepository(session.getLocalRepository());
                    projectBuildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());

                    ProjectBuildingResult result = projectBuilder.build(f, projectBuildingRequest);

                    getLog().debug("updateExcludesInDeps()");
                    modified = updateExcludesInDeps(result.getProject(), dependencies, transitiveDeps);
                }
            }

            project.setFile(dependencyReducedPomLocation);
        }
    }

    private void removeSystemScopedDependencies(Set<String> artifactsToRemove, List<Dependency> originalDependencies) {
        for (Dependency dependency : originalDependencies) {
            if (dependency.getScope() != null && dependency.getScope().equalsIgnoreCase("system")) {
                artifactsToRemove.add(getId(dependency));
            }
        }
    }

    private void addSystemScopedDependencyFromNonInterpolatedPom(
            List<Dependency> dependencies, List<Dependency> originalDependencies) {
        for (Dependency dependency : originalDependencies) {
            if (dependency.getScope() != null && dependency.getScope().equalsIgnoreCase("system")) {
                dependencies.add(dependency);
            }
        }
    }

    private Dependency createDependency(Artifact artifact) {
        Dependency dep = new Dependency();
        dep.setArtifactId(artifact.getArtifactId());
        if (artifact.hasClassifier()) {
            dep.setClassifier(artifact.getClassifier());
        }
        dep.setGroupId(artifact.getGroupId());
        dep.setOptional(artifact.isOptional());
        dep.setScope(artifact.getScope());
        dep.setType(artifact.getType());
        if (useBaseVersion) {
            dep.setVersion(artifact.getBaseVersion());
        } else {
            dep.setVersion(artifact.getVersion());
        }
        return dep;
    }

    private String getId(Artifact artifact) {
        return getId(artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), artifact.getClassifier());
    }

    private String getId(Dependency dependency) {
        return getId(
                dependency.getGroupId(), dependency.getArtifactId(), dependency.getType(), dependency.getClassifier());
    }

    private String getId(String groupId, String artifactId, String type, String classifier) {
        return groupId + ":" + artifactId + ":" + type + ":" + ((classifier != null) ? classifier : "");
    }

    public boolean updateExcludesInDeps(
            MavenProject project, List<Dependency> dependencies, List<Dependency> transitiveDeps)
            throws DependencyCollectionException {
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(RepositoryUtils.toArtifact(project.getArtifact()));
        collectRequest.setRepositories(project.getRemoteProjectRepositories());
        collectRequest.setDependencies(project.getDependencies().stream()
                .map(d -> RepositoryUtils.toDependency(
                        d, session.getRepositorySession().getArtifactTypeRegistry()))
                .collect(Collectors.toList()));
        if (project.getDependencyManagement() != null) {
            collectRequest.setManagedDependencies(project.getDependencyManagement().getDependencies().stream()
                    .map(d -> RepositoryUtils.toDependency(
                            d, session.getRepositorySession().getArtifactTypeRegistry()))
                    .collect(Collectors.toList()));
        }
        CollectResult result = repositorySystem.collectDependencies(session.getRepositorySession(), collectRequest);
        boolean modified = false;
        if (result.getRoot() != null) {
            for (DependencyNode n2 : result.getRoot().getChildren()) {
                String artifactId2 = getId(RepositoryUtils.toArtifact(n2.getArtifact()));

                for (DependencyNode n3 : n2.getChildren()) {
                    // stupid m-a Artifact that has no idea what it is: dependency or artifact?
                    Artifact artifact3 = RepositoryUtils.toArtifact(n3.getArtifact());
                    artifact3.setScope(n3.getDependency().getScope());
                    String artifactId3 = getId(artifact3);

                    // check if it really isn't in the list of original dependencies. Maven
                    // prior to 2.0.8 may grab versions from transients instead of
                    // from the direct deps in which case they would be marked included
                    // instead of OMITTED_FOR_DUPLICATE

                    // also, if not promoting the transitives, level 2's would be included
                    boolean found = false;
                    for (Dependency dep : transitiveDeps) {
                        if (getId(dep).equals(artifactId3)) {
                            found = true;
                            break;
                        }
                    }

                    // MSHADE-311: do not add exclusion for provided transitive dep
                    //       note: MSHADE-31 introduced the exclusion logic for promoteTransitiveDependencies=true,
                    //             but as of 3.2.1 promoteTransitiveDependencies has no effect for provided deps,
                    //             which makes this fix even possible (see also MSHADE-181)
                    if (!found && !"provided".equals(artifact3.getScope())) {
                        getLog().debug(String.format(
                                "dependency %s (scope %s) not found in transitive dependencies",
                                artifactId3, artifact3.getScope()));
                        for (Dependency dep : dependencies) {
                            if (getId(dep).equals(artifactId2)) {
                                // MSHADE-413: First check whether the exclusion has already been added,
                                // because it's meaningless to add it more than once. Certain cases
                                // can end up adding the exclusion "forever" and cause an endless loop
                                // rewriting the whole dependency-reduced-pom.xml file.
                                if (!dependencyHasExclusion(dep, artifact3)) {
                                    getLog().debug(String.format(
                                            "Adding exclusion for dependency %s (scope %s) " + "to %s (scope %s)",
                                            artifactId3, artifact3.getScope(), getId(dep), dep.getScope()));
                                    Exclusion exclusion = new Exclusion();
                                    exclusion.setArtifactId(artifact3.getArtifactId());
                                    exclusion.setGroupId(artifact3.getGroupId());
                                    dep.addExclusion(exclusion);
                                    modified = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return modified;
    }

    private boolean dependencyHasExclusion(Dependency dep, Artifact exclusionToCheck) {
        boolean containsExclusion = false;
        for (Exclusion existingExclusion : dep.getExclusions()) {
            if (existingExclusion.getGroupId().equals(exclusionToCheck.getGroupId())
                    && existingExclusion.getArtifactId().equals(exclusionToCheck.getArtifactId())) {
                containsExclusion = true;
                break;
            }
        }
        return containsExclusion;
    }

    private List<ResourceTransformer> toResourceTransformers(
            String shade, List<ResourceTransformer> resourceTransformers) {
        List<ResourceTransformer> forShade = new ArrayList<>();
        ManifestResourceTransformer lastMt = null;
        for (ResourceTransformer transformer : resourceTransformers) {
            if (!(transformer instanceof ManifestResourceTransformer)) {
                forShade.add(transformer);
            } else if (((ManifestResourceTransformer) transformer).isForShade(shade)) {
                final ManifestResourceTransformer mt = (ManifestResourceTransformer) transformer;
                if (mt.isUsedForDefaultShading() && lastMt != null && !lastMt.isUsedForDefaultShading()) {
                    continue; // skip, we already have a specific transformer
                }
                if (!mt.isUsedForDefaultShading() && lastMt != null && lastMt.isUsedForDefaultShading()) {
                    forShade.remove(lastMt);
                } else if (!mt.isUsedForDefaultShading() && lastMt != null) {
                    getLog().warn("Ambiguous manifest transformer definition for '" + shade + "': " + mt + " / "
                            + lastMt);
                }
                if (lastMt == null || !mt.isUsedForDefaultShading()) {
                    lastMt = mt;
                }
                forShade.add(transformer);
            }
        }
        return forShade;
    }
}
