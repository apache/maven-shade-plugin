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
package org.apache.maven.plugins.shade;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.AppendingTransformer;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.plugins.shade.resource.ServicesResourceTransformer;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.Os;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Objects.requireNonNull;
import static org.codehaus.plexus.util.FileUtils.forceMkdir;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class DefaultShaderTest {
    private static final String[] EXCLUDES =
            new String[] {"org/codehaus/plexus/util/xml/Xpp3Dom", "org/codehaus/plexus/util/xml/pull.*"};

    @TempDir
    File temporaryFolder;

    private static final String NEWLINE = "\n";

    @Test
    public void testNoopWhenNotRelocated() throws IOException, MojoExecutionException {
        File plexusJar = new File("src/test/jars/plexus-utils-1.4.1.jar");
        File shadedOutput = new File("target/foo-custom_testNoopWhenNotRelocated.jar");

        Set<File> jars = new LinkedHashSet<>();
        jars.add(new File("src/test/jars/test-project-1.0-SNAPSHOT.jar"));
        jars.add(plexusJar);

        Relocator relocator = new SimpleRelocator(
                "org/codehaus/plexus/util/cli",
                "relocated/plexus/util/cli",
                Collections.emptyList(),
                Collections.emptyList());

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(jars);
        shadeRequest.setRelocators(Collections.singletonList(relocator));
        shadeRequest.setResourceTransformers(Collections.emptyList());
        shadeRequest.setFilters(Collections.emptyList());
        shadeRequest.setUberJar(shadedOutput);

        DefaultShader shader = newShader();
        shader.shade(shadeRequest);

        try (JarFile originalJar = new JarFile(plexusJar);
                JarFile shadedJar = new JarFile(shadedOutput)) {
            // ASM processes all class files. In doing so, it modifies them, even when not relocating anything.
            // Before MSHADE-391, the processed files were written to the uber JAR, which did no harm, but made it
            // difficult to find out by simple file comparison, if a file was actually relocated or not. Now, Shade
            // makes sure to always write the original file if the class neither was relocated itself nor references
            // other, relocated classes. So we are checking for regressions here.
            assertTrue(areEqual(originalJar, shadedJar, "org/codehaus/plexus/util/Expand.class"));

            // Relocated files should always be different, because they contain different package names in their byte
            // code. We should verify this anyway, in order to avoid an existing class file from simply being moved to
            // another location without actually having been relocated internally.
            assertFalse(areEqual(
                    originalJar,
                    shadedJar,
                    "org/codehaus/plexus/util/cli/Arg.class",
                    "relocated/plexus/util/cli/Arg.class"));
        }
        int result = 0;
        for (String msg : debugMessages.getAllValues()) {
            if ("Rewrote class bytecode: org/codehaus/plexus/util/cli/Arg.class".equals(msg)) {
                result |= 1;
            } else if ("Keeping original class bytecode: org/codehaus/plexus/util/Expand.class".equals(msg)) {
                result |= 2;
            }
        }
        assertEquals(3 /* 1 | 2 */, result);
    }

    @Test
    public void testOverlappingResourcesAreLogged() throws IOException, MojoExecutionException {
        DefaultShader shader = newShader();

        // we will shade two jars and expect to see META-INF/MANIFEST.MF overlaps, this will always be true
        // but this can lead to a broken deployment if intended for OSGi or so, so even this should be logged
        Set<File> set = new LinkedHashSet<>();
        set.add(new File("src/test/jars/test-project-1.0-SNAPSHOT.jar"));
        set.add(new File("src/test/jars/plexus-utils-1.4.1.jar"));

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setRelocators(Collections.<Relocator>emptyList());
        shadeRequest.setResourceTransformers(Collections.<ResourceTransformer>emptyList());
        shadeRequest.setFilters(Collections.<Filter>emptyList());
        shadeRequest.setUberJar(new File("target/foo-custom_testOverlappingResourcesAreLogged.jar"));
        shader.shade(shadeRequest);

        assertThat(
                warnMessages.getAllValues(),
                hasItem(containsString(
                        "plexus-utils-1.4.1.jar, test-project-1.0-SNAPSHOT.jar define 1 overlapping resource:")));
        assertThat(warnMessages.getAllValues(), hasItem(containsString("- META-INF/MANIFEST.MF")));
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            assertThat(
                    debugMessages.getAllValues(),
                    hasItem(containsString(
                            "We have a duplicate META-INF/MANIFEST.MF in src\\test\\jars\\plexus-utils-1.4.1.jar")));
        } else {
            assertThat(
                    debugMessages.getAllValues(),
                    hasItem(containsString(
                            "We have a duplicate META-INF/MANIFEST.MF in src/test/jars/plexus-utils-1.4.1.jar")));
        }
    }

    @Test
    public void testOverlappingResourcesAreLoggedExceptATransformerHandlesIt() throws Exception {
        File temporaryFolder = Files.createTempDirectory("junit").toFile();
        try {
            Set<File> set = new LinkedHashSet<>();
            File j1 = newFile(temporaryFolder, "j1.jar");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(j1))) {
                jos.putNextEntry(new JarEntry("foo.txt"));
                jos.write("c1".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            File j2 = newFile(temporaryFolder, "j2.jar");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(j2))) {
                jos.putNextEntry(new JarEntry("foo.txt"));
                jos.write("c2".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            set.add(j1);
            set.add(j2);

            AppendingTransformer transformer = new AppendingTransformer();
            Field resource = AppendingTransformer.class.getDeclaredField("resource");
            resource.setAccessible(true);
            resource.set(transformer, "foo.txt");

            ShadeRequest shadeRequest = new ShadeRequest();
            shadeRequest.setJars(set);
            shadeRequest.setRelocators(Collections.emptyList());
            shadeRequest.setResourceTransformers(Collections.singletonList(transformer));
            shadeRequest.setFilters(Collections.emptyList());
            shadeRequest.setUberJar(new File("target/foo-custom_testOverlappingResourcesAreLogged.jar"));

            DefaultShader shaderWithTransformer = newShader();
            shaderWithTransformer.shade(shadeRequest);

            assertThat(warnMessages.getAllValues().size(), is(0));

            DefaultShader shaderWithoutTransformer = newShader();
            shadeRequest.setResourceTransformers(Collections.emptyList());
            shaderWithoutTransformer.shade(shadeRequest);

            assertThat(
                    warnMessages.getAllValues(),
                    hasItems(containsString("j1.jar, j2.jar define 1 overlapping resource:")));
            assertThat(warnMessages.getAllValues(), hasItems(containsString("- foo.txt")));
        } finally {
            temporaryFolder.delete();
        }
    }

    @Test
    public void testShaderWithDefaultShadedPattern() throws Exception {
        shaderWithPattern(null, new File("target/foo-default.jar"), EXCLUDES);
    }

    @Test
    public void testShaderWithStaticInitializedClass() throws Exception {
        Shader s = newShader();

        Set<File> set = new LinkedHashSet<>();

        set.add(new File("src/test/jars/test-artifact-1.0-SNAPSHOT.jar"));

        List<Relocator> relocators = new ArrayList<>();

        relocators.add(new SimpleRelocator("org.apache.maven.plugins.shade", null, null, null));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        List<Filter> filters = new ArrayList<>();

        File file = new File("target/testShaderWithStaticInitializedClass.jar");

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(file);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        s.shade(shadeRequest);

        try (URLClassLoader cl = new URLClassLoader(new URL[] {file.toURI().toURL()})) {
            Class<?> c = cl.loadClass("hidden.org.apache.maven.plugins.shade.Lib");
            Object o = c.newInstance();
            assertEquals("foo.bar/baz", c.getDeclaredField("CONSTANT").get(o));
        }
    }

    @Test
    public void testShaderWithCustomShadedPattern() throws Exception {
        shaderWithPattern("org/shaded/plexus/util", new File("target/foo-custom.jar"), EXCLUDES);
    }

    @Test
    public void testShaderWithoutExcludesShouldRemoveReferencesOfOriginalPattern() throws Exception {
        // FIXME: shaded jar should not include references to org/codehaus/* (empty dirs) or org.codehaus.* META-INF
        // files.
        shaderWithPattern(
                "org/shaded/plexus/util", new File("target/foo-custom-without-excludes.jar"), new String[] {});
    }

    @Test
    public void testHandleDirectory() throws Exception {
        final File dir = temporaryFolder;
        // explode src/test/jars/test-artifact-1.0-SNAPSHOT.jar in this temp dir
        try (JarInputStream in =
                new JarInputStream(Files.newInputStream(Paths.get("src/test/jars/test-artifact-1.0-SNAPSHOT.jar")))) {
            JarEntry nextJarEntry;
            while ((nextJarEntry = in.getNextJarEntry()) != null) {
                if (nextJarEntry.isDirectory()) {
                    continue;
                }
                File out = new File(dir, nextJarEntry.getName());
                forceMkdir(out.getParentFile());
                try (OutputStream outputStream = Files.newOutputStream(out.toPath())) {
                    IOUtil.copy(in, outputStream, (int) Math.max(nextJarEntry.getSize(), 512));
                }
            }
        }

        // do shade
        File shade = new File("target/testHandleDirectory.jar");
        shaderWithPattern("org/shaded/plexus/util", shade, new String[0], singleton(dir));

        // ensure directory was shaded properly
        try (JarFile jar = new JarFile(shade)) {
            List<String> entries = new ArrayList<>();
            Enumeration<JarEntry> jarEntryEnumeration = jar.entries();
            while (jarEntryEnumeration.hasMoreElements()) {
                JarEntry jarEntry = jarEntryEnumeration.nextElement();
                if (jarEntry.isDirectory()) {
                    continue;
                }
                entries.add(jarEntry.getName());
            }
            Collections.sort(entries);
            assertEquals(
                    asList(
                            "META-INF/maven/org.apache.maven.plugins.shade/test-artifact/pom.properties",
                            "META-INF/maven/org.apache.maven.plugins.shade/test-artifact/pom.xml",
                            "org/apache/maven/plugins/shade/Lib.class"),
                    entries);
        }
    }

    @Test
    public void testShaderWithRelocatedClassname() throws Exception {
        DefaultShader s = newShader();

        Set<File> set = new LinkedHashSet<>();

        set.add(new File("src/test/jars/test-project-1.0-SNAPSHOT.jar"));

        set.add(new File("src/test/jars/plexus-utils-1.4.1.jar"));

        List<Relocator> relocators = new ArrayList<>();

        relocators.add(
                new SimpleRelocator("org/codehaus/plexus/util/", "_plexus/util/__", null, Collections.emptyList()));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add(new ComponentsXmlResourceTransformer());

        List<Filter> filters = new ArrayList<>();

        File file = new File("target/foo-relocate-class.jar");

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(file);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        s.shade(shadeRequest);

        try (URLClassLoader cl = new URLClassLoader(new URL[] {file.toURI().toURL()})) {
            Class<?> c = cl.loadClass("_plexus.util.__StringUtils");
            // first, ensure it works:
            Object o = c.newInstance();
            assertEquals("", c.getMethod("clean", String.class).invoke(o, (String) null));

            // now, check that its source file was rewritten:
            String[] source = {null};
            ClassReader classReader = new ClassReader(cl.getResourceAsStream("_plexus/util/__StringUtils.class"));
            classReader.accept(
                    new ClassVisitor(Opcodes.ASM4) {
                        @Override
                        public void visitSource(String arg0, String arg1) {
                            super.visitSource(arg0, arg1);
                            source[0] = arg0;
                        }
                    },
                    ClassReader.SKIP_CODE);
            assertEquals("__StringUtils.java", source[0]);
        }
    }

    @Test
    public void testShaderWithNestedJar() throws Exception {
        File temporaryFolder = Files.createTempDirectory("junit").toFile();

        final String innerJarFileName = "inner.jar";
        File innerJar = newFile(temporaryFolder, innerJarFileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(innerJar.toPath()))) {
            jos.putNextEntry(new JarEntry("foo.txt"));
            jos.write("c1".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(new LinkedHashSet<>(Collections.singleton(innerJar)));
        shadeRequest.setFilters(Collections.emptyList());
        shadeRequest.setRelocators(Collections.emptyList());
        shadeRequest.setResourceTransformers(Collections.emptyList());
        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        shadeRequest.setUberJar(shadedFile);

        DefaultShader shader = newShader();
        shader.shade(shadeRequest);

        FileTime lastModified = FileTime.from(
                Files.getLastModifiedTime(shadedFile.toPath()).toInstant().minus(5, ChronoUnit.SECONDS));

        Files.setLastModifiedTime(shadedFile.toPath(), lastModified);

        shader.shade(shadeRequest);
        assertEquals(lastModified, Files.getLastModifiedTime(shadedFile.toPath()));

        temporaryFolder.delete();
    }

    @Test
    public void testShaderNoOverwrite() throws Exception {
        File temporaryFolder = Files.createTempDirectory("junit").toFile();

        final String innerJarFileName = "inner.jar";
        File innerJar = newFile(temporaryFolder, innerJarFileName);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(innerJar))) {
            jos.putNextEntry(new JarEntry("foo.txt"));
            jos.write("c1".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        File outerJar = newFile(temporaryFolder, "outer.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outerJar))) {
            FileInputStream innerStream = new FileInputStream(innerJar);
            byte[] bytes = IOUtil.toByteArray(innerStream, 32 * 1024);
            innerStream.close();
            writeEntryWithoutCompression(innerJarFileName, bytes, jos);
        }

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(new LinkedHashSet<>(Collections.singleton(outerJar)));
        shadeRequest.setFilters(new ArrayList<>());
        shadeRequest.setRelocators(new ArrayList<>());
        shadeRequest.setResourceTransformers(new ArrayList<>());
        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        shadeRequest.setUberJar(shadedFile);

        DefaultShader shader = newShader();
        shader.shade(shadeRequest);

        JarFile shadedJarFile = new JarFile(shadedFile);
        JarEntry entry = shadedJarFile.getJarEntry(innerJarFileName);

        // After shading, entry compression method should not be changed.
        Assertions.assertEquals(entry.getMethod(), ZipEntry.STORED);

        temporaryFolder.delete();
    }

    @Test
    public void testShaderWithDuplicateService() throws Exception {
        File temporaryFolder = Files.createTempDirectory("junit").toFile();

        String serviceEntryName = "META-INF/services/my.foo.Service";
        String serviceEntryValue = "my.foo.impl.Service1";

        File innerJar1 = newFile(temporaryFolder, "inner1.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(innerJar1.toPath()))) {
            jos.putNextEntry(new JarEntry(serviceEntryName));
            jos.write((serviceEntryValue + NEWLINE).getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        File innerJar2 = newFile(temporaryFolder, "inner2.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(innerJar2.toPath()))) {
            jos.putNextEntry(new JarEntry(serviceEntryName));
            jos.write((serviceEntryValue + NEWLINE).getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(new LinkedHashSet<>(Arrays.asList(innerJar1, innerJar2)));
        shadeRequest.setFilters(Collections.emptyList());
        shadeRequest.setRelocators(Collections.emptyList());
        shadeRequest.setResourceTransformers(Collections.singletonList(new ServicesResourceTransformer()));
        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        shadeRequest.setUberJar(shadedFile);

        DefaultShader shader = newShader();
        shader.shade(shadeRequest);

        JarFile shadedJarFile = new JarFile(shadedFile);
        JarEntry entry = shadedJarFile.getJarEntry(serviceEntryName);

        List<String> lines = new BufferedReader(
                        new InputStreamReader(shadedJarFile.getInputStream(entry), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.toList());

        // After shading, there should be a single input
        Assertions.assertEquals(Collections.singletonList(serviceEntryValue), lines);

        temporaryFolder.delete();
    }

    @Test
    public void preservesExplicitMultiReleaseFalseInDiscardMode() throws Exception {
        File primary = createJar(newFile(temporaryFolder, "primary.jar"), false, "primary.txt");
        File dependency = createJar(newFile(temporaryFolder, "dependency.jar"), true, "dependency.txt");
        File shadedFile = newFile(temporaryFolder, "shaded.jar");

        ManifestResourceTransformer manifestTransformer = new ManifestResourceTransformer();
        HashMap<String, Object> entries = new HashMap<>();
        entries.put("Multi-Release", "false");
        manifestTransformer.setManifestEntries(entries);

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        shadeRequest.setPrimaryArtifact(primary);
        shadeRequest.setFilters(Collections.emptyList());
        shadeRequest.setRelocators(Collections.emptyList());
        shadeRequest.setResourceTransformers(Collections.singletonList(manifestTransformer));
        shadeRequest.setUberJar(shadedFile);

        DefaultShader shader = newShader();
        shader.shade(shadeRequest);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals("false", shadedJar.getManifest().getMainAttributes().getValue("Multi-Release"));
        }
        assertFalse(warnMessages.getAllValues().stream()
                .anyMatch(message -> message.contains("Multi-Release: false is overridden")));

        temporaryFolder.delete();
    }

    @Test
    public void propagatesMultiReleaseFromEmbeddedJarInMergeMode() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(
                    output, "app.module", new String[] {"dep.module"}, new String[] {"app/api"}, null, null);
            writeClass(output, "app/api/App");
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest dependencyManifest = new Manifest();
        dependencyManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        dependencyManifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), dependencyManifest)) {
            writeModuleDescriptor(output, "dep.module", new String[0], new String[0], null, null);
            writeClass(output, "META-INF/versions/11/dep/api/Dependency.class", "dep/api/Dependency", Opcodes.V11);
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ManifestResourceTransformer manifestTransformer = new ManifestResourceTransformer();
        HashMap<String, Object> entries = new HashMap<>();
        entries.put("Multi-Release", "false");
        manifestTransformer.setManifestEntries(entries);

        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.singletonList(manifestTransformer));
        request.setUberJar(shadedFile);

        newShader().shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals("true", shadedJar.getManifest().getMainAttributes().getValue("Multi-Release"));
        }
        assertThat(warnMessages.getAllValues(), hasItem(containsString("Multi-Release: false is overridden")));

        temporaryFolder.delete();
    }

    @Test
    public void infersPlatformRequirementsFromAutomaticModules() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(
                    output, "app.module", new String[] {"dep.auto"}, new String[] {"app/api"}, null, null);
            writeClass(output, "app/api/App");
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest dependencyManifest = new Manifest();
        dependencyManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        dependencyManifest.getMainAttributes().putValue("Automatic-Module-Name", "dep.auto");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), dependencyManifest)) {
            writeClassReferencing(output, "dep/AutomaticDependency", "java/sql/Driver");
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(shadedFile);

        newShader().shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            Set<String> requirements = readModuleRequirements(shadedJar, "module-info.class", "app.module");
            assertTrue(requirements.contains("java.sql"));
            assertFalse(requirements.contains("dep.auto"));
        }

        temporaryFolder.delete();
    }

    @Test
    public void mergesAutomaticModuleServicesAndInfersServiceLoaderUses() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest manifest = automaticModuleManifest("dep.auto", false);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()), manifest)) {
            writeClass(output, "spi/FirstService");
            writeClass(output, "spi/SecondService");
            writeClass(output, "dep/Provider");
            writeServiceLoaderConsumer(output, "dep/Consumer", "spi/FirstService", "spi/SecondService");
            writeServiceConfiguration(output, "spi.FirstService", "dep.Provider");
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = moduleMergeRequest(primary, dependency, Collections.emptySet(), shadedFile);

        newShader().shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals(
                    new LinkedHashSet<>(Arrays.asList("spi/FirstService", "spi/SecondService")),
                    readModuleUses(shadedJar, "module-info.class", "app.module"));
            assertEquals(
                    Collections.singleton("dep/Provider"),
                    readModuleProviders(shadedJar, "module-info.class", "app.module", "spi/FirstService"));
        }

        temporaryFolder.delete();
    }

    @Test
    public void excludesFilteredAutomaticModuleServiceConfiguration() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest manifest = automaticModuleManifest("dep.auto", false);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()), manifest)) {
            writeClass(output, "spi/Service");
            writeClass(output, "dep/Provider");
            writeServiceConfiguration(output, "spi.Service", "dep.Provider");
        }

        Filter filter = mock(Filter.class);
        when(filter.canFilter(dependency)).thenReturn(true);
        when(filter.isFiltered("META-INF/services/spi.Service")).thenReturn(true);

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = moduleMergeRequest(primary, dependency, Collections.emptySet(), shadedFile);
        request.setFilters(Collections.singletonList(filter));

        newShader().shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertTrue(readModuleProviders(shadedJar, "module-info.class", "app.module", "spi/Service")
                    .isEmpty());
            assertTrue(shadedJar.getJarEntry("META-INF/services/spi.Service") == null);
        }

        temporaryFolder.delete();
    }

    @Test
    public void failsWhenAutomaticModuleServiceLoaderUseIsDynamic() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest manifest = automaticModuleManifest("dep.auto", false);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()), manifest)) {
            writeDynamicServiceLoaderConsumer(output, "dep/DynamicConsumer");
        }

        ShadeRequest request =
                moduleMergeRequest(primary, dependency, Collections.emptySet(), newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(exception.getMessage(), containsString("dep.DynamicConsumer"));
        assertThat(exception.getMessage(), containsString("ServiceLoader.load"));
        assertThat(exception.getMessage(), containsString("service type cannot be determined"));

        temporaryFolder.delete();
    }

    @Test
    public void failsWhenAutomaticModuleUsesServiceLoaderMethodHandle() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest manifest = automaticModuleManifest("dep.auto", false);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()), manifest)) {
            writeServiceLoaderMethodHandle(output, "dep/MethodHandleConsumer");
        }

        ShadeRequest request =
                moduleMergeRequest(primary, dependency, Collections.emptySet(), newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(exception.getMessage(), containsString("dep.MethodHandleConsumer"));
        assertThat(exception.getMessage(), containsString("ServiceLoader method handle"));

        temporaryFolder.delete();
    }

    @Test
    public void skipsNonArchiveDependencyAnalysisInputs() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }
        File dependency = automaticModule(
                newFile(temporaryFolder, "dependency.jar"), "dep.auto", "dep/AutomaticDependency", "java/sql/Driver");
        File nativeLibrary = newFile(temporaryFolder, "libnative.so");
        Files.write(nativeLibrary.toPath(), new byte[] {0x7f, 'E', 'L', 'F'});

        ShadeRequest request = moduleMergeRequest(
                primary, dependency, Collections.singleton(nativeLibrary), newFile(temporaryFolder, "shaded.jar"));

        newShader().shade(request);

        temporaryFolder.delete();
    }

    @Test
    public void rejectsBytecodeNewerThanTheAnalysisJdk() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());
        Assumptions.assumeTrue(runtimeFeature() < 26);

        int futureRelease = runtimeFeature() + 1;
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }
        File dependency = automaticMultiReleaseModule(
                newFile(temporaryFolder, "dependency.jar"),
                futureRelease,
                "dep/FutureDependency",
                "future/platform/Type");

        ShadeRequest request =
                moduleMergeRequest(primary, dependency, Collections.emptySet(), newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));

        assertThat(exception.getMessage(), containsString("requires Java " + futureRelease + " platform data"));
        assertThat(exception.getMessage(), containsString("analysisJdkToolchain"));

        temporaryFolder.delete();
    }

    @Test
    public void failsWhenAutomaticModuleReferenceCannotBeResolved() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }

        File dependency = automaticModule(
                newFile(temporaryFolder, "dependency.jar"), "dep.auto", "dep/AutomaticDependency", "missing/Type");

        ShadeRequest request =
                moduleMergeRequest(primary, dependency, Collections.emptySet(), newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(exception.getMessage(), containsString("dep.AutomaticDependency"));
        assertThat(exception.getMessage(), containsString("missing.Type"));
        assertThat(exception.getMessage(), containsString("not present in the shaded output"));

        temporaryFolder.delete();
    }

    @Test
    public void failsWhenAutomaticModuleReferenceHasAmbiguousOwners() throws Exception {
        Assumptions.assumeTrue(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }

        File dependency = automaticModule(
                newFile(temporaryFolder, "dependency.jar"), "dep.auto", "dep/AutomaticDependency", "external/Type");
        File externalOne =
                automaticModule(newFile(temporaryFolder, "external-one.jar"), "external.one", "external/Type", null);
        File externalTwo =
                automaticModule(newFile(temporaryFolder, "external-two.jar"), "external.two", "external/Type", null);

        ShadeRequest request = moduleMergeRequest(
                primary,
                dependency,
                new LinkedHashSet<>(Arrays.asList(externalOne, externalTwo)),
                newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(exception.getMessage(), containsString("external.Type"));
        assertThat(exception.getMessage(), containsString("external.one"));
        assertThat(exception.getMessage(), containsString("external.two"));
        assertThat(exception.getMessage(), containsString("owned by multiple modules"));

        temporaryFolder.delete();
    }

    @Test
    public void failsAutomaticModuleInferenceWithoutModularAnalysisJdk() throws Exception {
        Assumptions.assumeFalse(isModularRuntime());

        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[] {"dep.auto"}, new String[0], null, null);
        }
        File dependency = automaticModule(
                newFile(temporaryFolder, "dependency.jar"), "dep.auto", "dep/AutomaticDependency", "java/sql/Driver");
        ShadeRequest request =
                moduleMergeRequest(primary, dependency, Collections.emptySet(), newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(exception.getMessage(), containsString("Invalid module-info analysis JDK"));
        assertThat(exception.getMessage(), containsString("missing jmods/java.base.jmod"));
        assertThat(exception.getMessage(), containsString("analysisJdkToolchain"));

        temporaryFolder.delete();
    }

    @Test
    public void mergesModuleDescriptorsUsingPrimaryModuleBoundary() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(
                    output,
                    "app.module",
                    new String[] {"dep.module", "external.module"},
                    new String[] {"app/api"},
                    null,
                    null);
            writeClass(output, "app/api/App");
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            writeModuleDescriptor(
                    output,
                    "dep.module",
                    new String[] {"external.module"},
                    new String[] {"dep/api"},
                    "spi/Service",
                    "dep/internal/Provider");
            writeClass(output, "dep/api/Dependency");
            writeClass(output, "dep/internal/Provider");
            writeClass(output, "dep/impl/Helper");
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.singletonList(new SimpleRelocator("dep", "hidden.dep", null, null)));
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(shadedFile);

        newShader().shade(request);

        final Set<String> requires = new LinkedHashSet<>();
        final Set<String> exports = new LinkedHashSet<>();
        final Set<String> packages = new LinkedHashSet<>();
        final Set<String> providers = new LinkedHashSet<>();
        try (JarFile shadedJar = new JarFile(shadedFile);
                InputStream descriptor = shadedJar.getInputStream(shadedJar.getJarEntry("module-info.class"))) {
            new ClassReader(descriptor)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    assertEquals("app.module", name);
                                    return new ModuleVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitRequire(String module, int access, String version) {
                                            requires.add(module);
                                        }

                                        @Override
                                        public void visitExport(String packaze, int access, String... modules) {
                                            exports.add(packaze);
                                        }

                                        @Override
                                        public void visitPackage(String packaze) {
                                            packages.add(packaze);
                                        }

                                        @Override
                                        public void visitProvide(String service, String... implementations) {
                                            providers.addAll(Arrays.asList(implementations));
                                        }
                                    };
                                }
                            },
                            0);

            assertEquals(
                    "app.module", shadedJar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
            assertTrue(shadedJar.getEntry("hidden/dep/internal/Provider.class") != null);
            assertTrue(shadedJar.getEntry("hidden/dep/impl/Helper.class") != null);
        }

        assertTrue(requires.contains("java.base"));
        assertTrue(requires.contains("external.module"));
        assertFalse(requires.contains("dep.module"));
        assertEquals(Collections.singleton("app/api"), exports);
        assertEquals(
                new LinkedHashSet<>(
                        Arrays.asList("app/api", "hidden/dep/api", "hidden/dep/impl", "hidden/dep/internal")),
                packages);
        assertEquals(Collections.singleton("hidden/dep/internal/Provider"), providers);

        temporaryFolder.delete();
    }

    @Test
    public void raisesModuleFloorForLaterProvidersAndPlatformRequirements() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(
                    output, "app.module", new String[] {"dep.module"}, new String[] {"app/api"}, null, null);
            writeClass(output, "app/api/App");
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest dependencyManifest = new Manifest();
        dependencyManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        dependencyManifest.getMainAttributes().putValue("Multi-Release", "true");
        dependencyManifest.getMainAttributes().putValue("Automatic-Module-Name", "dep.module");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), dependencyManifest)) {
            writeModuleDescriptor(
                    output,
                    "module-info.class",
                    Opcodes.V9,
                    "dep.module",
                    new String[0],
                    null,
                    new String[0],
                    "spi/Service",
                    "dep/internal/StableProvider");
            writeModuleDescriptor(
                    output,
                    "META-INF/versions/17/module-info.class",
                    Opcodes.V17,
                    "dep.module",
                    new String[0],
                    "jdk.incubator.vector",
                    new String[0],
                    "spi/Service",
                    "dep/internal/StableProvider",
                    "dep/versioned/LaterProvider");
            writeClass(output, "dep/internal/StableProvider");
            writeClass(
                    output,
                    "META-INF/versions/17/dep/versioned/LaterProvider.class",
                    "dep/versioned/LaterProvider",
                    Opcodes.V17);
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.singletonList(new SimpleRelocator("dep", "hidden.dep", null, null)));
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(shadedFile);

        newShader().shade(request);

        final Set<String> requirements = new LinkedHashSet<>();
        final Set<String> packages = new LinkedHashSet<>();
        final Set<String> providers = new LinkedHashSet<>();
        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals("true", shadedJar.getManifest().getMainAttributes().getValue("Multi-Release"));
            assertEquals(
                    "app.module", shadedJar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
            assertTrue(shadedJar.getJarEntry("module-info.class") == null);
            assertTrue(shadedJar.getJarEntry("META-INF/versions/17/module-info.class") != null);
            assertTrue(shadedJar.getJarEntry("META-INF/versions/17/hidden/dep/versioned/LaterProvider.class") != null);

            try (InputStream descriptor =
                    shadedJar.getInputStream(shadedJar.getJarEntry("META-INF/versions/17/module-info.class"))) {
                new ClassReader(descriptor)
                        .accept(
                                new ClassVisitor(Opcodes.ASM9) {
                                    @Override
                                    public ModuleVisitor visitModule(String name, int access, String version) {
                                        assertEquals("app.module", name);
                                        return new ModuleVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visitRequire(String module, int access, String version) {
                                                if (!"java.base".equals(module)) {
                                                    requirements.add(module);
                                                    assertEquals(0, access & Opcodes.ACC_TRANSITIVE);
                                                }
                                            }

                                            @Override
                                            public void visitPackage(String packaze) {
                                                packages.add(packaze);
                                            }

                                            @Override
                                            public void visitProvide(String service, String... implementations) {
                                                providers.addAll(Arrays.asList(implementations));
                                            }
                                        };
                                    }
                                },
                                0);
            }

            JarEntry service = shadedJar.getJarEntry("META-INF/services/spi.Service");
            assertTrue(service != null);
            List<String> serviceProviders = new BufferedReader(
                            new InputStreamReader(shadedJar.getInputStream(service), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.toList());
            assertEquals(Collections.singletonList("hidden.dep.internal.StableProvider"), serviceProviders);
        }

        assertEquals(Collections.singleton("jdk.incubator.vector"), requirements);
        assertEquals(
                new LinkedHashSet<>(Arrays.asList("app/api", "hidden/dep/internal", "hidden/dep/versioned")), packages);
        assertEquals(
                new LinkedHashSet<>(
                        Arrays.asList("hidden/dep/internal/StableProvider", "hidden/dep/versioned/LaterProvider")),
                providers);
        assertThat(
                warnMessages.getAllValues(),
                hasItems(
                        containsString("Raising the module descriptor floor for app.module from Java 9 to Java 17"),
                        containsString("provider hidden.dep.versioned.LaterProvider for spi.Service")));
        assertFalse(warnMessages.getAllValues().stream()
                .anyMatch(message -> message.contains("transitive platform requirement jdk.incubator.vector")));

        temporaryFolder.delete();
    }

    @Test
    public void failsWhenVersionedProviderCannotBeBridgedBelowRaisedFloor() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(
                    output, "app.module", new String[] {"dep.module"}, new String[] {"app/api"}, null, null);
            writeClass(output, "app/api/App");
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        Manifest dependencyManifest = new Manifest();
        dependencyManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        dependencyManifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), dependencyManifest)) {
            writeModuleDescriptor(
                    output, "module-info.class", Opcodes.V9, "dep.module", new String[0], null, new String[0], null);
            writeModuleDescriptor(
                    output,
                    "META-INF/versions/11/module-info.class",
                    Opcodes.V11,
                    "dep.module",
                    new String[0],
                    null,
                    new String[0],
                    "spi/Service",
                    "dep/versioned/Provider11");
            writeModuleDescriptor(
                    output,
                    "META-INF/versions/17/module-info.class",
                    Opcodes.V17,
                    "dep.module",
                    new String[0],
                    null,
                    new String[0],
                    "spi/Service",
                    "dep/versioned/Provider11",
                    "dep/versioned/Provider17");
            writeClass(
                    output,
                    "META-INF/versions/11/dep/versioned/Provider11.class",
                    "dep/versioned/Provider11",
                    Opcodes.V11);
            writeClass(
                    output,
                    "META-INF/versions/17/dep/versioned/Provider17.class",
                    "dep/versioned/Provider17",
                    Opcodes.V17);
        }

        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(exception.getMessage(), containsString("dep.versioned.Provider11"));
        assertThat(exception.getMessage(), containsString("cannot be exposed while app.module is automatic"));
        assertThat(exception.getMessage(), containsString("META-INF/services cannot be versioned"));

        temporaryFolder.delete();
    }

    @Test
    public void writesRootAndVersionedModuleDescriptors() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        Manifest primaryManifest = new Manifest();
        primaryManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        primaryManifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()), primaryManifest)) {
            writeModuleDescriptor(
                    output,
                    "module-info.class",
                    Opcodes.V9,
                    "app.module",
                    new String[] {"dep.module"},
                    null,
                    new String[] {"app/api"},
                    null);
            writeModuleDescriptor(
                    output,
                    "META-INF/versions/17/module-info.class",
                    Opcodes.V17,
                    "app.module",
                    new String[] {"dep.module", "jdk.unsupported"},
                    null,
                    new String[] {"app/api"},
                    null);
            writeClass(output, "app/api/App");
            writeClass(
                    output, "META-INF/versions/17/app/versioned/Feature.class", "app/versioned/Feature", Opcodes.V17);
        }

        File dependency = newFile(temporaryFolder, "dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            writeModuleDescriptor(output, "dep.module", new String[0], new String[0], null, null);
            writeClass(output, "dep/api/Dependency");
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Arrays.asList(
                new SimpleRelocator("app.versioned", "hidden.app.versioned", null, null),
                new SimpleRelocator("dep", "hidden.dep", null, null)));
        request.setResourceTransformers(Collections.emptyList());
        ModuleInfoConfiguration moduleInfo = new ModuleInfoConfiguration();
        moduleInfo.setModuleName("shaded.app.module");
        request.setModuleInfoConfiguration(moduleInfo);
        request.setUberJar(shadedFile);

        newShader().shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals("true", shadedJar.getManifest().getMainAttributes().getValue("Multi-Release"));
            assertEquals(
                    "shaded.app.module",
                    shadedJar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
            assertEquals(
                    Collections.singleton("java.base"),
                    readModuleRequirements(shadedJar, "module-info.class", "shaded.app.module"));
            assertEquals(
                    new LinkedHashSet<>(Arrays.asList("java.base", "jdk.unsupported")),
                    readModuleRequirements(shadedJar, "META-INF/versions/17/module-info.class", "shaded.app.module"));
            Set<String> expectedPackages =
                    new LinkedHashSet<>(Arrays.asList("app/api", "hidden/app/versioned", "hidden/dep/api"));
            assertEquals(expectedPackages, readModulePackages(shadedJar, "module-info.class", "shaded.app.module"));
            assertEquals(
                    expectedPackages,
                    readModulePackages(shadedJar, "META-INF/versions/17/module-info.class", "shaded.app.module"));
            assertTrue(shadedJar.getJarEntry("META-INF/versions/17/hidden/app/versioned/Feature.class") != null);
            assertTrue(shadedJar.getJarEntry("hidden/dep/api/Dependency.class") != null);
            assertTrue(shadedJar.getJarEntry("META-INF/versions/17/app/versioned/Feature.class") == null);
            assertTrue(shadedJar.getJarEntry("dep/api/Dependency.class") == null);
        }

        temporaryFolder.delete();
    }

    @Test
    public void failsWhenFilteringInvalidatesPrimaryModuleExports() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeModuleDescriptor(output, "app.module", new String[0], new String[] {"app/api"}, null, null);
            writeClass(output, "app/api/App");
        }

        Filter filter = mock(Filter.class);
        when(filter.canFilter(primary)).thenReturn(true);
        when(filter.isFiltered("app/api/App.class")).thenReturn(true);

        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Collections.singleton(primary)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.singletonList(filter));
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(newFile(temporaryFolder, "shaded.jar"));

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> newShader().shade(request));
        assertThat(
                exception.getMessage(),
                containsString("exported package app.api from the primary descriptor is absent"));

        temporaryFolder.delete();
    }

    @Test
    public void preservesModuleTargetAndResolutionButDropsHashes() throws Exception {
        File primary = newFile(temporaryFolder, "primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
            ModuleVisitor module = writer.visitModule("app.module", 0, null);
            module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
            module.visitExport("app/api", 0);
            module.visitPackage("app/api");
            module.visitEnd();
            writer.visitAttribute(new TestModuleTargetAttribute("linux-amd64"));
            writer.visitAttribute(new TestModuleResolutionAttribute(1));
            writer.visitAttribute(new TestModuleHashesAttribute());
            writer.visitEnd();
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(writer.toByteArray());
            writeClass(output, "app/api/App");
        }

        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Collections.singleton(primary)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(shadedFile);

        newShader().shade(request);

        final TestModuleTargetAttribute[] target = new TestModuleTargetAttribute[1];
        final TestModuleResolutionAttribute[] resolution = new TestModuleResolutionAttribute[1];
        final Set<String> otherAttributes = new LinkedHashSet<>();
        try (JarFile shadedJar = new JarFile(shadedFile);
                InputStream descriptor = shadedJar.getInputStream(shadedJar.getJarEntry("module-info.class"))) {
            new ClassReader(descriptor)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitAttribute(Attribute attribute) {
                                    if (attribute instanceof TestModuleTargetAttribute) {
                                        target[0] = (TestModuleTargetAttribute) attribute;
                                    } else if (attribute instanceof TestModuleResolutionAttribute) {
                                        resolution[0] = (TestModuleResolutionAttribute) attribute;
                                    } else {
                                        otherAttributes.add(attribute.type);
                                    }
                                }
                            },
                            new Attribute[] {
                                new TestModuleTargetAttribute(),
                                new TestModuleResolutionAttribute(),
                                new TestModuleHashesAttribute()
                            },
                            0);
        }

        assertEquals("linux-amd64", target[0].targetPlatform);
        assertEquals(1, resolution[0].resolutionFlags);
        assertFalse(otherAttributes.contains("ModuleHashes"));
        assertThat(warnMessages.getAllValues(), hasItem(containsString("Dropping ModuleHashes")));

        temporaryFolder.delete();
    }

    private File createJar(File file, boolean multiRelease, String entryName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file.toPath()), manifest)) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(entryName.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private File automaticModule(File file, String moduleName, String className, String referencedClass)
            throws IOException {
        Manifest manifest = automaticModuleManifest(moduleName, false);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file.toPath()), manifest)) {
            if (referencedClass == null) {
                writeClass(output, className);
            } else {
                writeClassReferencing(output, className, referencedClass);
            }
        }
        return file;
    }

    private File automaticMultiReleaseModule(File file, int release, String className, String referencedClass)
            throws IOException {
        Manifest manifest = automaticModuleManifest("dep.auto", true);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file.toPath()), manifest)) {
            writeClassReferencing(
                    output,
                    "META-INF/versions/" + release + '/' + className + ".class",
                    className,
                    referencedClass,
                    release + 44);
        }
        return file;
    }

    private Manifest automaticModuleManifest(String moduleName, boolean multiRelease) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        return manifest;
    }

    private ShadeRequest moduleMergeRequest(
            File primary, File dependency, Set<File> dependencyAnalysisArtifacts, File output) {
        ShadeRequest request = new ShadeRequest();
        request.setJars(new LinkedHashSet<>(Arrays.asList(primary, dependency)));
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setDependencyAnalysisArtifacts(dependencyAnalysisArtifacts);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.emptyList());
        request.setUberJar(output);
        return request;
    }

    private boolean isModularRuntime() {
        return !System.getProperty("java.specification.version").startsWith("1.");
    }

    private int runtimeFeature() {
        String version = System.getProperty("java.specification.version");
        return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    }

    private void writeModuleDescriptor(
            JarOutputStream output,
            String moduleName,
            String[] requires,
            String[] exports,
            String service,
            String provider)
            throws IOException {
        writeModuleDescriptor(
                output, "module-info.class", Opcodes.V9, moduleName, requires, null, exports, service, provider);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void writeModuleDescriptor(
            JarOutputStream output,
            String entryName,
            int classVersion,
            String moduleName,
            String[] requires,
            String transitiveRequirement,
            String[] exports,
            String service,
            String... providers)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(classVersion, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule(moduleName, 0, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        for (String requirement : requires) {
            module.visitRequire(requirement, 0, null);
        }
        if (transitiveRequirement != null) {
            module.visitRequire(transitiveRequirement, Opcodes.ACC_TRANSITIVE, null);
        }
        for (String exportedPackage : exports) {
            module.visitExport(exportedPackage, 0);
            module.visitPackage(exportedPackage);
        }
        if (service != null) {
            module.visitUse(service);
            module.visitProvide(service, providers);
            for (String provider : providers) {
                module.visitPackage(provider.substring(0, provider.lastIndexOf('/')));
            }
        }
        module.visitEnd();
        writer.visitEnd();

        output.putNextEntry(new JarEntry(entryName));
        output.write(writer.toByteArray());
    }

    private void writeClass(JarOutputStream output, String name) throws IOException {
        writeClass(output, name + ".class", name, Opcodes.V9);
    }

    private void writeClassReferencing(JarOutputStream output, String name, String referencedClass) throws IOException {
        writeClassReferencing(output, name + ".class", name, referencedClass, Opcodes.V9);
    }

    private void writeClassReferencing(
            JarOutputStream output, String entryName, String name, String referencedClass, int classVersion)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(classVersion, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "reference", 'L' + referencedClass + ';', null, null)
                .visitEnd();
        writer.visitEnd();
        output.putNextEntry(new JarEntry(entryName));
        output.write(writer.toByteArray());
    }

    private void writeServiceLoaderConsumer(
            JarOutputStream output, String name, String firstService, String secondService) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load", "(Z)V", null, null);
        method.visitCode();
        Label second = new Label();
        Label join = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, second);
        method.visitLdcInsn(Type.getObjectType(firstService));
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(second);
        method.visitLdcInsn(Type.getObjectType(secondService));
        method.visitLabel(join);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(Type.getObjectType(firstService));
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitLdcInsn(Type.getObjectType(secondService));
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(Type.getObjectType(firstService));
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/ServiceLoader",
                "loadInstalled",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        output.putNextEntry(new JarEntry(name + ".class"));
        output.write(writer.toByteArray());
    }

    private void writeDynamicServiceLoaderConsumer(JarOutputStream output, String name) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load", "(Ljava/lang/Class;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        output.putNextEntry(new JarEntry(name + ".class"));
        output.write(writer.toByteArray());
    }

    private void writeServiceLoaderMethodHandle(JarOutputStream output, String name) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reference", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn(new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false));
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        output.putNextEntry(new JarEntry(name + ".class"));
        output.write(writer.toByteArray());
    }

    private void writeServiceConfiguration(JarOutputStream output, String service, String... providers)
            throws IOException {
        output.putNextEntry(new JarEntry("META-INF/services/" + service));
        output.write((String.join("\n", providers) + '\n').getBytes(StandardCharsets.UTF_8));
    }

    private void writeClass(JarOutputStream output, String entryName, String name, int classVersion)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(classVersion, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        output.putNextEntry(new JarEntry(entryName));
        output.write(writer.toByteArray());
    }

    private Set<String> readModuleRequirements(JarFile jar, String entryName, String expectedModuleName)
            throws IOException {
        Set<String> requirements = new LinkedHashSet<>();
        JarEntry entry = requireNonNull(jar.getJarEntry(entryName), entryName + " in " + jar.getName());
        try (InputStream descriptor = jar.getInputStream(entry)) {
            new ClassReader(descriptor)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    assertEquals(expectedModuleName, name);
                                    return new ModuleVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitRequire(String module, int access, String version) {
                                            requirements.add(module);
                                        }
                                    };
                                }
                            },
                            0);
        }
        return requirements;
    }

    private Set<String> readModulePackages(JarFile jar, String entryName, String expectedModuleName)
            throws IOException {
        Set<String> packages = new LinkedHashSet<>();
        JarEntry entry = requireNonNull(jar.getJarEntry(entryName), entryName + " in " + jar.getName());
        try (InputStream descriptor = jar.getInputStream(entry)) {
            new ClassReader(descriptor)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    assertEquals(expectedModuleName, name);
                                    return new ModuleVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitPackage(String packaze) {
                                            packages.add(packaze);
                                        }
                                    };
                                }
                            },
                            0);
        }
        return packages;
    }

    private Set<String> readModuleUses(JarFile jar, String entryName, String expectedModuleName) throws IOException {
        Set<String> uses = new LinkedHashSet<>();
        JarEntry entry = requireNonNull(jar.getJarEntry(entryName), entryName + " in " + jar.getName());
        try (InputStream descriptor = jar.getInputStream(entry)) {
            new ClassReader(descriptor)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    assertEquals(expectedModuleName, name);
                                    return new ModuleVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitUse(String service) {
                                            uses.add(service);
                                        }
                                    };
                                }
                            },
                            0);
        }
        return uses;
    }

    private Set<String> readModuleProviders(
            JarFile jar, String entryName, String expectedModuleName, String expectedService) throws IOException {
        Set<String> providers = new LinkedHashSet<>();
        JarEntry entry = requireNonNull(jar.getJarEntry(entryName), entryName + " in " + jar.getName());
        try (InputStream descriptor = jar.getInputStream(entry)) {
            new ClassReader(descriptor)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    assertEquals(expectedModuleName, name);
                                    return new ModuleVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitProvide(String service, String... implementations) {
                                            if (expectedService.equals(service)) {
                                                providers.addAll(Arrays.asList(implementations));
                                            }
                                        }
                                    };
                                }
                            },
                            0);
        }
        return providers;
    }

    @Test
    public void testShaderWithSmallEntries() throws Exception {
        File temporaryFolder = Files.createTempDirectory("junit").toFile();

        final String innerJarFileName = "inner.jar";
        int len;
        File innerJar = newFile(temporaryFolder, innerJarFileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(innerJar.toPath()))) {
            jos.putNextEntry(new JarEntry("foo.txt"));
            byte[] bytes = "c1".getBytes(StandardCharsets.UTF_8);
            len = bytes.length;
            jos.write(bytes);
            jos.closeEntry();
        }

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(new LinkedHashSet<>(Collections.singleton(innerJar)));
        shadeRequest.setFilters(new ArrayList<>());
        shadeRequest.setRelocators(new ArrayList<>());
        shadeRequest.setResourceTransformers(new ArrayList<>());
        File shadedFile = newFile(temporaryFolder, "shaded.jar");
        shadeRequest.setUberJar(shadedFile);

        DefaultShader shader = newShader();
        shader.shade(shadeRequest);

        JarFile shadedJarFile = new JarFile(shadedFile);
        JarEntry entry = shadedJarFile.getJarEntry("foo.txt");

        // After shading, entry compression method should not be changed.
        Assertions.assertEquals(entry.getSize(), len);

        temporaryFolder.delete();
    }

    private void writeEntryWithoutCompression(String entryName, byte[] entryBytes, JarOutputStream jos)
            throws IOException {
        final JarEntry entry = new JarEntry(entryName);
        final int size = entryBytes.length;
        final CRC32 crc = new CRC32();
        crc.update(entryBytes, 0, size);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setMethod(ZipEntry.STORED);
        entry.setCrc(crc.getValue());
        jos.putNextEntry(entry);
        jos.write(entryBytes);
        jos.closeEntry();
    }

    private void shaderWithPattern(String shadedPattern, File jar, String[] excludes) throws Exception {
        Set<File> set = new LinkedHashSet<>();
        set.add(new File("src/test/jars/test-project-1.0-SNAPSHOT.jar"));
        set.add(new File("src/test/jars/plexus-utils-1.4.1.jar"));
        shaderWithPattern(shadedPattern, jar, excludes, set);
    }

    private void shaderWithPattern(String shadedPattern, File jar, String[] excludes, Set<File> set) throws Exception {
        DefaultShader s = newShader();

        List<Relocator> relocators = new ArrayList<>();

        relocators.add(new SimpleRelocator("org/codehaus/plexus/util", shadedPattern, null, Arrays.asList(excludes)));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add(new ComponentsXmlResourceTransformer());

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(jar);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        s.shade(shadeRequest);
    }

    private static final class TestModuleTargetAttribute extends Attribute {
        private String targetPlatform;

        private TestModuleTargetAttribute() {
            this(null);
        }

        private TestModuleTargetAttribute(String targetPlatform) {
            super("ModuleTarget");
            this.targetPlatform = targetPlatform;
        }

        @Override
        protected Attribute read(
                ClassReader classReader,
                int offset,
                int length,
                char[] charBuffer,
                int codeAttributeOffset,
                Label[] labels) {
            return new TestModuleTargetAttribute(classReader.readUTF8(offset, charBuffer));
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            return new ByteVector().putShort(classWriter.newUTF8(targetPlatform));
        }
    }

    private static final class TestModuleResolutionAttribute extends Attribute {
        private int resolutionFlags;

        private TestModuleResolutionAttribute() {
            this(0);
        }

        private TestModuleResolutionAttribute(int resolutionFlags) {
            super("ModuleResolution");
            this.resolutionFlags = resolutionFlags;
        }

        @Override
        protected Attribute read(
                ClassReader classReader,
                int offset,
                int length,
                char[] charBuffer,
                int codeAttributeOffset,
                Label[] labels) {
            return new TestModuleResolutionAttribute(classReader.readUnsignedShort(offset));
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            return new ByteVector().putShort(resolutionFlags);
        }
    }

    private static final class TestModuleHashesAttribute extends Attribute {
        private TestModuleHashesAttribute() {
            super("ModuleHashes");
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            return new ByteVector().putShort(classWriter.newUTF8("SHA-256")).putShort(0);
        }
    }

    private DefaultShader newShader() {
        return new DefaultShader(mockLogger());
    }

    private ArgumentCaptor<String> debugMessages;

    private ArgumentCaptor<String> warnMessages;

    private Logger mockLogger() {
        debugMessages = ArgumentCaptor.forClass(String.class);
        warnMessages = ArgumentCaptor.forClass(String.class);
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);
        doNothing().when(logger).debug(debugMessages.capture());
        doNothing().when(logger).warn(warnMessages.capture());
        doNothing().when(logger).warn(warnMessages.capture(), any(Object.class));
        return logger;
    }

    private boolean areEqual(final JarFile jar1, final JarFile jar2, final String entry) throws IOException {
        return areEqual(jar1, jar2, entry, entry);
    }

    private boolean areEqual(final JarFile jar1, final JarFile jar2, final String entry1, String entry2)
            throws IOException {
        try (InputStream s1 = jar1.getInputStream(
                        requireNonNull(jar1.getJarEntry(entry1), entry1 + " in " + jar1.getName()));
                InputStream s2 = jar2.getInputStream(
                        requireNonNull(jar2.getJarEntry(entry2), entry2 + " in " + jar2.getName()))) {
            return Arrays.equals(IOUtil.toByteArray(s1), IOUtil.toByteArray(s2));
        }
    }

    private static File newFile(File parent, String child) throws IOException {
        File result = new File(parent, child);
        result.createNewFile();
        return result;
    }
}
