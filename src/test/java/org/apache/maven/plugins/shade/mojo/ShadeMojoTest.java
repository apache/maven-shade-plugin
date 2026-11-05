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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.shade.ModuleInfoConfiguration;
import org.apache.maven.plugins.shade.ModuleInfoMode;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SerializedLambdaRelocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ApacheNoticeResourceTransformer;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.PlexusContainer;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.apache.maven.api.plugin.testing.MojoExtension.getBasedir;
import static org.apache.maven.api.plugin.testing.MojoExtension.setVariableValueToObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
@MojoTest
class ShadeMojoTest {
    @Inject
    private PlexusContainer container;

    @Inject
    private Shader shader;

    @Inject
    private ArtifactHandler artifactHandler;

    @Inject
    private MavenProject project;

    @Inject
    private ArtifactHandlerManager artifactHandlerManager;

    @Test
    void testManifestTransformerSelection() throws Exception {
        final ShadeMojo mojo = new ShadeMojo();
        final Method m = ShadeMojo.class.getDeclaredMethod("toResourceTransformers", String.class, List.class);
        m.setAccessible(true);

        final ManifestResourceTransformer defaultTfr = new ManifestResourceTransformer() {
            @Override
            public String toString() // when test fails junit does a toString so easier to read errors this way
                    {
                return "default";
            }
        };
        final ManifestResourceTransformer testsTfr1 = new ManifestResourceTransformer() {
            @Override
            public String toString() {
                return "t1";
            }
        };
        testsTfr1.setForShade("tests");
        final ManifestResourceTransformer testsTfr2 = new ManifestResourceTransformer() {
            @Override
            public String toString() {
                return "t2";
            }
        };
        testsTfr2.setForShade("tests");

        assertEquals(singletonList(defaultTfr), m.invoke(mojo, "jar", asList(defaultTfr, testsTfr1, testsTfr2)));
        assertEquals(asList(testsTfr1, testsTfr2), m.invoke(mojo, "tests", asList(defaultTfr, testsTfr1, testsTfr2)));
        assertEquals(asList(testsTfr1, testsTfr2), m.invoke(mojo, "tests", asList(testsTfr1, defaultTfr, testsTfr2)));
        assertEquals(asList(testsTfr1, testsTfr2), m.invoke(mojo, "tests", asList(testsTfr1, testsTfr2, defaultTfr)));
    }

    @Test
    void testSourceRequestsDiscardModuleInfo() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        setVariableValueToObject(mojo, "moduleInfoMode", "mErGe");
        Method method = ShadeMojo.class.getDeclaredMethod(
                "createShadeSourcesRequest", String.class, Set.class, File.class, List.class, List.class, List.class);
        method.setAccessible(true);

        ShadeRequest request = (ShadeRequest) method.invoke(
                mojo,
                "sources-jar",
                Collections.emptySet(),
                new File("target/sources.jar"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        assertEquals(ModuleInfoMode.DISCARD, request.getModuleInfoMode());
        assertTrue(request.getDependencyAnalysisArtifacts().isEmpty());
    }

    @Test
    void testSelectsConfiguredModuleInfoAnalysisJdkToolchain() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAnalysisJdkToolchain(Collections.singletonMap("version", "21"));
        MavenSession session = mock(MavenSession.class);
        ToolchainManager manager = mock(ToolchainManager.class);
        Toolchain toolchain = mock(Toolchain.class);
        File javaHome = new File(System.getProperty("java.home")).getCanonicalFile();
        when(manager.getToolchains(session, "jdk", configuration.getAnalysisJdkToolchain()))
                .thenReturn(Collections.singletonList(toolchain));
        when(toolchain.findTool("javac")).thenReturn(new File(new File(javaHome, "bin"), "javac").getPath());
        setVariableValueToObject(mojo, "moduleInfo", configuration);
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "toolchainManager", manager);

        Method method = ShadeMojo.class.getDeclaredMethod("resolveModuleInfoAnalysisJdkHome");
        method.setAccessible(true);
        assertEquals(javaHome, method.invoke(mojo));
        verify(manager).getToolchains(session, "jdk", configuration.getAnalysisJdkToolchain());
    }

    @Test
    void testRejectsMissingModuleInfoAnalysisJdkToolchain() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAnalysisJdkToolchain(Collections.singletonMap("version", "99"));
        MavenSession session = mock(MavenSession.class);
        ToolchainManager manager = mock(ToolchainManager.class);
        when(manager.getToolchains(session, "jdk", configuration.getAnalysisJdkToolchain()))
                .thenReturn(Collections.emptyList());
        setVariableValueToObject(mojo, "moduleInfo", configuration);
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "toolchainManager", manager);

        Method method = ShadeMojo.class.getDeclaredMethod("resolveModuleInfoAnalysisJdkHome");
        method.setAccessible(true);
        try {
            method.invoke(mojo);
            fail("Expected a missing toolchain failure");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause().getMessage().contains("No JDK toolchain matches"));
            assertTrue(e.getCause().getMessage().contains("version=99"));
        }
    }

    /**
     * MSHADE-456: a POM-declared {@code <dependency>} of type {@code test-jar} has no classifier set
     * explicitly, but resolves to classifier {@code tests} once Maven turns it into an {@link Artifact}
     * (see {@link DefaultArtifact}, which falls back to {@link ArtifactHandler#getClassifier()} when
     * constructed with a {@code null} classifier). The id computed from the {@code Dependency} must match
     * the id computed from the resolved {@code Artifact}, otherwise a shaded test-jar dependency is never
     * removed from the dependency reduced POM.
     */
    @Test
    void testGetIdForTestJarDependencyMatchesResolvedArtifact() throws Exception {
        final ShadeMojo mojo = new ShadeMojo();
        setVariableValueToObject(mojo, "artifactHandlerManager", artifactHandlerManager);

        final Dependency dependency = new Dependency();
        dependency.setGroupId("org.apache.maven.its");
        dependency.setArtifactId("foo");
        dependency.setVersion("1.0");
        dependency.setType("test-jar");
        // no classifier set here, exactly as a user would write it in the POM

        final Method getIdForDependency = ShadeMojo.class.getDeclaredMethod("getId", Dependency.class);
        getIdForDependency.setAccessible(true);
        final String dependencyId = (String) getIdForDependency.invoke(mojo, dependency);

        assertEquals("org.apache.maven.its:foo:test-jar:tests", dependencyId);

        final ArtifactHandler testJarHandler = container.lookup(ArtifactHandler.class, "test-jar");
        final Artifact artifact = new DefaultArtifact(
                "org.apache.maven.its",
                "foo",
                VersionRange.createFromVersion("1.0"),
                "compile",
                "test-jar",
                null,
                testJarHandler);

        final Method getIdForArtifact = ShadeMojo.class.getDeclaredMethod("getId", Artifact.class);
        getIdForArtifact.setAccessible(true);
        final String artifactId = (String) getIdForArtifact.invoke(mojo, artifact);

        assertEquals(dependencyId, artifactId);
    }

    /**
     * A dependency with an ordinary type keeps relying on its explicit (possibly absent) classifier,
     * unaffected by the MSHADE-456 fix for type {@code test-jar}.
     */
    @Test
    void testGetIdForRegularDependencyKeepsExplicitClassifier() throws Exception {
        final ShadeMojo mojo = new ShadeMojo();
        setVariableValueToObject(mojo, "artifactHandlerManager", artifactHandlerManager);

        final Dependency dependency = new Dependency();
        dependency.setGroupId("org.apache.maven.its");
        dependency.setArtifactId("bar");
        dependency.setVersion("1.0");
        dependency.setType("jar");

        final Method getIdForDependency = ShadeMojo.class.getDeclaredMethod("getId", Dependency.class);
        getIdForDependency.setAccessible(true);

        assertEquals("org.apache.maven.its:bar:jar:", getIdForDependency.invoke(mojo, dependency));

        dependency.setClassifier("linux");
        assertEquals("org.apache.maven.its:bar:jar:linux", getIdForDependency.invoke(mojo, dependency));
    }

    @Test
    void testShaderWithDefaultShadedPattern() throws Exception {
        shaderWithPattern(null, new File("target/foo-default.jar"));
    }

    @Test
    void testShaderWithCustomShadedPattern() throws Exception {
        shaderWithPattern("org/shaded/plexus/util", new File("target/foo-custom.jar"));
    }

    @Test
    void testShaderWithExclusions() throws Exception {
        File jarFile = new File(getBasedir(), "target/unit/foo-bar.jar");

        Set<File> set = new LinkedHashSet<>();
        set.add(new File(getBasedir(), "src/test/jars/test-artifact-1.0-SNAPSHOT.jar"));

        List<Relocator> relocators = new ArrayList<>();
        relocators.add(new SimpleRelocator(
                "org.codehaus.plexus.util",
                "hidden",
                null,
                Arrays.asList("org.codehaus.plexus.util.xml.Xpp3Dom", "org.codehaus.plexus.util.xml.pull.*")));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(jarFile);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        shader.shade(shadeRequest);

        try (URLClassLoader cl = new URLClassLoader(new URL[] {jarFile.toURI().toURL()})) {
            Class<?> c = cl.loadClass("org.apache.maven.plugins.shade.Lib");

            Field field = c.getDeclaredField("CLASS_REALM_PACKAGE_IMPORT");
            assertEquals("org.codehaus.plexus.util.xml.pull", field.get(null));

            Method method = c.getDeclaredMethod("getClassRealmPackageImport");
            assertEquals("org.codehaus.plexus.util.xml.pull", method.invoke(null));
        }
    }

    /**
     * Tests if a Filter is installed correctly, also if createSourcesJar is set to true.
     *
     * @throws Exception
     */
    @Test
    void testShadeWithFilter(@InjectMojo(goal = "shade") ShadeMojo mojo) throws Exception {
        // configure the MavenProject
        Artifact artifact = new DefaultArtifact(
                "org.apache.myfaces.core",
                "myfaces-impl",
                VersionRange.createFromVersion("2.0.1-SNAPSHOT"),
                "compile",
                "jar",
                null,
                artifactHandler);
        artifact.setFile(new File("myfaces-impl-2.0.1-SNAPSHOT.jar"));
        project.setArtifact(artifact);

        DefaultRepositorySystemSession repositorySystemSession = MavenRepositorySystemUtils.newSession();
        repositorySystemSession.setLocalRepositoryManager(new SimpleLocalRepositoryManagerFactory()
                .newInstance(repositorySystemSession, new LocalRepository(new File("target/local-repo/"))));
        MavenSession mavenSession = new MavenSession(
                container,
                repositorySystemSession,
                mock(MavenExecutionRequest.class),
                mock(MavenExecutionResult.class));

        setVariableValueToObject(mojo, "session", mavenSession);

        // set createSourcesJar = true
        setVariableValueToObject(mojo, "createSourcesJar", true);

        RepositorySystem repositorySystem = mock(RepositorySystem.class);
        setVariableValueToObject(mojo, "repositorySystem", repositorySystem);

        ArtifactResult artifactResult = new ArtifactResult(
                new ArtifactRequest(mock(org.eclipse.aether.artifact.Artifact.class), Collections.emptyList(), ""));
        org.eclipse.aether.artifact.Artifact result = new org.eclipse.aether.artifact.DefaultArtifact(
                        "org.apache.myfaces.core:myfaces-impl:jar:sources:2.0.1-SNAPSHOT")
                .setFile(new File("myfaces-impl-2.0.1-SNAPSHOT-sources.jar"));
        artifactResult.setArtifact(result);
        when(repositorySystem.resolveArtifact(eq(mavenSession.getRepositorySession()), any(ArtifactRequest.class)))
                .thenReturn(artifactResult);

        // create and configure the ArchiveFilter
        ArchiveFilter archiveFilter = new ArchiveFilter();
        Field archiveFilterArtifact = ArchiveFilter.class.getDeclaredField("artifact");
        archiveFilterArtifact.setAccessible(true);
        archiveFilterArtifact.set(archiveFilter, "org.apache.myfaces.core:myfaces-impl");

        // add ArchiveFilter to mojo
        Field filtersField = ShadeMojo.class.getDeclaredField("filters");
        filtersField.setAccessible(true);
        filtersField.set(mojo, new ArchiveFilter[] {archiveFilter});

        // invoke getFilters()
        Method getFilters = ShadeMojo.class.getDeclaredMethod("getFilters", List.class, ArtifactSelector.class);
        getFilters.setAccessible(true);
        ArtifactSelector selector = new ArtifactSelector(Collections.emptyList(), Collections.emptyList(), null);
        List<Filter> filters = (List<Filter>) getFilters.invoke(mojo, Collections.emptyList(), selector);

        // assertions - there must be one filter
        assertEquals(1, filters.size());

        // the filter must be able to filter the binary and the sources jar
        Filter filter = filters.get(0);
        assertTrue(filter.canFilter(new File("myfaces-impl-2.0.1-SNAPSHOT.jar"))); // binary jar
        assertTrue(filter.canFilter(new File("myfaces-impl-2.0.1-SNAPSHOT-sources.jar"))); // sources jar
    }

    /**
     * The shaded main artifact must land in {@code outputDirectory}, exactly like the shaded sources, test and
     * test-sources artifacts, even when the pre-shade artifact file lives elsewhere.
     */
    @Test
    void testShadedMainArtifactFileUsesOutputDirectory() throws Exception {
        ShadeMojo mojo = new ShadeMojo();

        File outputDirectory = new File(getBasedir(), "target/testShadedMainArtifactFileUsesOutputDirectory/custom");
        setVariableValueToObject(mojo, "outputDirectory", outputDirectory);

        Artifact artifact = new DefaultArtifact(
                "org.apache.maven.plugins.shade.test",
                "test-project",
                VersionRange.createFromVersion("1.0"),
                "compile",
                "jar",
                null,
                artifactHandler);
        artifact.setFile(new File(
                getBasedir(), "target/testShadedMainArtifactFileUsesOutputDirectory/build/test-project-1.0.jar"));
        project.setArtifact(artifact);
        setVariableValueToObject(mojo, "project", project);

        Method shadedMainArtifactFile = ShadeMojo.class.getDeclaredMethod("shadedMainArtifactFile");
        shadedMainArtifactFile.setAccessible(true);

        assertEquals(new File(outputDirectory, "test-project-1.0.jar"), shadedMainArtifactFile.invoke(mojo));
    }

    /**
     * Reproduces MSHADE-354: when the pre-shade artifact does not already live where the shaded artifact belongs
     * (e.g. {@code outputDirectory} differs from the default build directory), {@code replaceFile} must still move
     * the shaded content to the configured destination and back up the pre-shade content there, rather than
     * leaving the shaded jar behind in the pre-shade artifact's original directory.
     */
    @Test
    void testReplaceFileMovesShadedJarAcrossDirectories() throws Exception {
        ShadeMojo mojo = new ShadeMojo();

        File baseDir = new File(getBasedir(), "target/testReplaceFileMovesShadedJarAcrossDirectories");
        File buildDirectory = new File(baseDir, "build");
        File outputDirectory = new File(baseDir, "custom");
        buildDirectory.mkdirs();
        outputDirectory.mkdirs();
        setVariableValueToObject(mojo, "outputDirectory", outputDirectory);

        File preShadeArtifact = new File(buildDirectory, "app-1.0.jar");
        Files.write(preShadeArtifact.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        File shadedJar = new File(outputDirectory, "app-1.0-shaded.jar");
        Files.write(shadedJar.toPath(), "shaded".getBytes(StandardCharsets.UTF_8));

        File destFile = new File(outputDirectory, "app-1.0.jar");

        Method replaceFile = ShadeMojo.class.getDeclaredMethod("replaceFile", File.class, File.class, File.class);
        replaceFile.setAccessible(true);
        replaceFile.invoke(mojo, destFile, preShadeArtifact, shadedJar);

        assertFalse(
                preShadeArtifact.exists(), "the pre-shade artifact must be moved out of the default build directory");
        assertTrue(destFile.exists(), "the shaded artifact must land in the configured outputDirectory");
        assertEquals("shaded", new String(Files.readAllBytes(destFile.toPath()), StandardCharsets.UTF_8));

        File backup = new File(outputDirectory, "original-app-1.0.jar");
        assertTrue(backup.exists(), "the pre-shade artifact must be preserved in outputDirectory");
        assertEquals("original", new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * MSHADE-382: a skipped execution returns before it touches the project, the shader or any file.
     */
    @Test
    void testSkipDoesNothing() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        Log log = mock(Log.class);
        Shader skippedShader = mock(Shader.class);
        mojo.setLog(log);
        // no project: the mojo fails with a NullPointerException as soon as it goes on after the skip
        setVariableValueToObject(mojo, "skip", true);
        setVariableValueToObject(mojo, "shader", skippedShader);

        mojo.execute();

        verify(log).info("Shading has been skipped.");
        verify(skippedShader, never()).shade(any(ShadeRequest.class));
    }

    @Test
    void testUnmatchedArtifactSetPatternsAreReported() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        Log log = mock(Log.class);
        mojo.setLog(log);

        File directory = new File(getBasedir(), "target/testUnmatchedArtifactSetPatternsAreReported");
        directory.mkdirs();
        File mainJar = new File(directory, "main-1.0.jar");
        File dependencyJar = new File(directory, "slf4j-api-1.7.36.jar");
        writeEmptyJar(mainJar);
        writeEmptyJar(dependencyJar);

        MavenProject mavenProject = new MavenProject();
        Artifact main = new DefaultArtifact(
                "org.apache.maven.its",
                "main",
                VersionRange.createFromVersion("1.0"),
                "compile",
                "jar",
                null,
                artifactHandler);
        main.setFile(mainJar);
        mavenProject.setArtifact(main);
        Artifact dependency = new DefaultArtifact(
                "org.slf4j",
                "slf4j-api",
                VersionRange.createFromVersion("1.7.36"),
                "compile",
                "jar",
                null,
                artifactHandler);
        dependency.setFile(dependencyJar);
        mavenProject.setArtifacts(new HashSet<>(singletonList(dependency)));

        ArtifactSet artifactSet = new ArtifactSet();
        setVariableValueToObject(
                artifactSet, "includes", new LinkedHashSet<>(asList("org.slf4j:slf4j-api", "no.such:artifact")));
        setVariableValueToObject(artifactSet, "excludes", new LinkedHashSet<>(singletonList("no.such:other")));
        setVariableValueToObject(mojo, "project", mavenProject);
        setVariableValueToObject(mojo, "artifactSet", artifactSet);
        setVariableValueToObject(mojo, "shader", mock(Shader.class));
        setVariableValueToObject(mojo, "projectHelper", mock(MavenProjectHelper.class));
        setVariableValueToObject(mojo, "shadedArtifactAttached", true);
        setVariableValueToObject(mojo, "shadedClassifierName", "shaded");
        setVariableValueToObject(mojo, "outputFile", new File(directory, "shaded.jar"));

        mojo.execute();

        verify(log).warn("No artifact matching artifactSet include no.such:artifact");
        verify(log).info("No artifact matching artifactSet exclude no.such:other");
        verify(log, never()).warn("No artifact matching artifactSet include org.slf4j:slf4j-api");
    }

    /**
     * The generated NOTICE ends its copyright line with the year of {@code project.build.outputTimestamp}.
     */
    @Test
    void testApacheNoticeTransformerGetsTimestampYear() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        ApacheNoticeResourceTransformer notice = new ApacheNoticeResourceTransformer();
        setVariableValueToObject(mojo, "project", new MavenProject());
        setVariableValueToObject(mojo, "transformers", new ResourceTransformer[] {notice});
        setVariableValueToObject(mojo, "outputTimestamp", "2019-06-01T00:00:00Z");

        Method getResourceTransformers = ShadeMojo.class.getDeclaredMethod("getResourceTransformers");
        getResourceTransformers.setAccessible(true);
        assertEquals(singletonList(notice), getResourceTransformers.invoke(mojo));

        notice.processResource(
                "META-INF/NOTICE",
                new java.io.ByteArrayInputStream("Dependency notice\n".getBytes(StandardCharsets.UTF_8)),
                Collections.<Relocator>emptyList(),
                0);
        java.io.ByteArrayOutputStream jar = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(jar)) {
            notice.modifyOutputStream(out);
        }
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jar.toByteArray()))) {
            JarEntry entry = in.getNextJarEntry();
            assertEquals("META-INF/NOTICE", entry.getName());
            java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                content.write(buffer, 0, read);
            }
            String text = new String(content.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(text.contains("Copyright 2006-2019 The Apache Software Foundation"), text);
        }
    }

    /**
     * {@code <shadeSerializedLambda>} adds a {@link SerializedLambdaRelocator} next to the
     * {@link SimpleRelocator} of the same relocation, and only for the relocation that sets it.
     */
    @Test
    void testShadeSerializedLambdaAddsSerializedLambdaRelocator() throws Exception {
        PackageRelocation plain = new PackageRelocation();
        setVariableValueToObject(plain, "pattern", "org.plain");
        setVariableValueToObject(plain, "shadedPattern", "shaded.plain");
        PackageRelocation lambda = new PackageRelocation();
        setVariableValueToObject(lambda, "pattern", "org.lambda");
        setVariableValueToObject(lambda, "shadedPattern", "shaded.lambda");
        setVariableValueToObject(lambda, "shadeSerializedLambda", true);

        ShadeMojo mojo = new ShadeMojo();
        setVariableValueToObject(mojo, "relocations", new PackageRelocation[] {plain, lambda});
        Method getRelocators = ShadeMojo.class.getDeclaredMethod("getRelocators");
        getRelocators.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Relocator> relocators = (List<Relocator>) getRelocators.invoke(mojo);

        assertEquals(3, relocators.size());
        assertEquals(SimpleRelocator.class, relocators.get(0).getClass());
        assertEquals(SimpleRelocator.class, relocators.get(1).getClass());
        assertEquals(SerializedLambdaRelocator.class, relocators.get(2).getClass());
    }

    private static void writeEmptyJar(File file) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
            out.putNextEntry(new JarEntry("README"));
            out.write('x');
        }
    }

    private void shaderWithPattern(String shadedPattern, File jar) throws Exception {

        Set<File> set = new LinkedHashSet<>();

        set.add(new File(getBasedir(), "src/test/jars/test-project-1.0-SNAPSHOT.jar"));

        set.add(new File(getBasedir(), "src/test/jars/plexus-utils-1.4.1.jar"));

        List<Relocator> relocators = new ArrayList<>();

        relocators.add(new SimpleRelocator(
                "org/codehaus/plexus/util",
                shadedPattern,
                null,
                Arrays.asList("org/codehaus/plexus/util/xml/Xpp3Dom", "org/codehaus/plexus/util/xml/pull.*")));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add(new ComponentsXmlResourceTransformer());

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(jar);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        shader.shade(shadeRequest);
    }
}
