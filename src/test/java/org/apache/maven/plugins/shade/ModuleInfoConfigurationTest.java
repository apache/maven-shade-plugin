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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ModuleInfoConfigurationTest {
    @TempDir
    File temporaryFolder;

    @Test
    public void overridesOutputModuleNameAndRemovesOriginalSelfRequirement() throws Exception {
        File primary = primaryModule("dep.module");
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            writeDescriptor(output, "dep.module", new String[] {"app.module"}, null);
        }

        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setModuleName("shaded.app.module");
        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency);
        request.setModuleInfoConfiguration(configuration);

        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals("shaded.app.module", readModuleName(shadedJar));
            assertEquals(
                    "shaded.app.module",
                    shadedJar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
            Set<String> requirements =
                    readRequirementAccess(shadedJar, "shaded.app.module").keySet();
            assertFalse(requirements.contains("app.module"));
            assertFalse(requirements.contains("dep.module"));
        }
    }

    @Test
    public void rejectsInvalidOutputModuleNames() throws Exception {
        File primary = primaryModule();
        int index = 0;
        for (String invalid : Arrays.asList("", "bad-name", "int.module", "_")) {
            ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
            configuration.setModuleName(invalid);
            ShadeRequest request = moduleRequest(primary, newFile("invalid-" + index++ + ".jar"));
            request.setModuleInfoConfiguration(configuration);

            MojoExecutionException exception = assertThrows(MojoExecutionException.class, () -> shade(request));
            assertTrue(exception.getMessage().contains("Invalid moduleInfo.moduleName '" + invalid + "'"));
        }
    }

    @Test
    public void mergesExplicitAndAutomaticModuleBoundariesWhenRequested() throws Exception {
        Assumptions.assumeFalse(System.getProperty("java.specification.version").startsWith("1."));

        File primary = primaryModule("dep.module", "automatic.module");

        File dependency = newFile("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
            ModuleVisitor module = writer.visitModule("dep.module", 0, null);
            module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
            module.visitExport("dep/api", 0);
            module.visitOpen("dep/reflect", 0);
            module.visitPackage("dep/api");
            module.visitPackage("dep/reflect");
            module.visitEnd();
            writer.visitEnd();
            writeEntry(output, "module-info.class", writer.toByteArray());
            writeClass(output, "dep/api/Dependency");
            writeClass(output, "dep/reflect/ReflectiveType");
            writeClass(output, "dep/internal/HiddenType");
        }

        File automatic = newFile("automatic.jar");
        Manifest manifest = automaticModuleManifest("automatic.module");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(automatic.toPath()), manifest)) {
            writeClass(output, "automatic/api/PublicType");
        }

        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency, automatic);
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setPublicBoundary("merge");
        request.setModuleInfoConfiguration(configuration);
        request.setRelocators(Collections.singletonList(new SimpleRelocator("dep", "hidden.dep", null, null)));

        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals(
                    new LinkedHashSet<>(Arrays.asList("app/api", "automatic/api", "hidden/dep/api")),
                    readPackages(shadedJar, Directive.EXPORT));
            assertEquals(
                    new LinkedHashSet<>(Arrays.asList("automatic/api", "hidden/dep/reflect")),
                    readPackages(shadedJar, Directive.OPEN));
            assertFalse(readPackages(shadedJar, Directive.EXPORT).contains("hidden/dep/internal"));
        }
    }

    @Test
    public void downgradesEmbeddedTransitiveRequirementsUnlessExplicitlyPromoted() throws Exception {
        File primary = newFile("primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeDescriptor(output, "app.module", new String[] {"dep.module"}, "external.primary", "app/api");
            writeClass(output, "app/api/App");
        }
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            writeDescriptor(output, "dep.module", new String[0], "external.embedded");
        }

        File defaultOutput = newFile("default.jar");
        ShadeRequest request = moduleRequest(primary, defaultOutput, dependency);
        shade(request);

        try (JarFile shadedJar = new JarFile(defaultOutput)) {
            Map<String, Integer> access = readRequirementAccess(shadedJar);
            assertTrue((access.get("external.primary") & Opcodes.ACC_TRANSITIVE) != 0);
            assertEquals(0, access.get("external.embedded") & Opcodes.ACC_TRANSITIVE);
        }

        ModuleInfoConfiguration.Requirement promotion = new ModuleInfoConfiguration.Requirement();
        promotion.setModule("external.embedded");
        promotion.setTransitive(true);
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAdditionalRequires(Collections.singletonList(promotion));
        request.setModuleInfoConfiguration(configuration);
        File promotedOutput = newFile("promoted.jar");
        request.setUberJar(promotedOutput);
        shade(request);

        try (JarFile shadedJar = new JarFile(promotedOutput)) {
            assertTrue((readRequirementAccess(shadedJar).get("external.embedded") & Opcodes.ACC_TRANSITIVE) != 0);
        }
    }

    @Test
    public void appliesDescriptorOverridesAndAcknowledgesDynamicServiceUses() throws Exception {
        Assumptions.assumeFalse(System.getProperty("java.specification.version").startsWith("1."));

        File primary = newFile("primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeDescriptor(output, "app.module", new String[] {"dep.auto"}, null);
            writeClass(output, "app/internal/InternalType");
        }
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), automaticModuleManifest("dep.auto"))) {
            writeDynamicServiceLoaderConsumer(output, "dep/DynamicConsumer");
        }
        File external = newFile("external.jar");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(external.toPath()), automaticModuleManifest("external.module"))) {
            writeClass(output, "spi/DynamicService");
        }

        ModuleInfoConfiguration.PackageDirective additionalExport = new ModuleInfoConfiguration.PackageDirective();
        additionalExport.setPackageName("app.internal");
        ModuleInfoConfiguration.PackageDirective additionalOpen = new ModuleInfoConfiguration.PackageDirective();
        additionalOpen.setPackageName("app.internal");
        ModuleInfoConfiguration.Requirement additionalRequire = new ModuleInfoConfiguration.Requirement();
        additionalRequire.setModule("external.optional");
        additionalRequire.setStaticRequirement(true);
        additionalRequire.setTransitive(true);
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAdditionalExports(Collections.singletonList(additionalExport));
        configuration.setAdditionalOpens(Collections.singletonList(additionalOpen));
        configuration.setAdditionalRequires(Collections.singletonList(additionalRequire));
        configuration.setDynamicUses(Collections.singleton("spi.DynamicService"));

        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency);
        request.setDependencyAnalysisArtifacts(Collections.singleton(external));
        request.setModuleInfoConfiguration(configuration);
        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals(Collections.singleton("app/internal"), readPackages(shadedJar, Directive.EXPORT));
            assertEquals(Collections.singleton("app/internal"), readPackages(shadedJar, Directive.OPEN));
            assertEquals(Collections.singleton("spi/DynamicService"), readUses(shadedJar));
            int access = readRequirementAccess(shadedJar).get("external.optional");
            assertTrue((access & Opcodes.ACC_STATIC_PHASE) != 0);
            assertTrue((access & Opcodes.ACC_TRANSITIVE) != 0);
            assertTrue(readRequirementAccess(shadedJar).containsKey("external.module"));
        }
    }

    @Test
    public void keepsExternalRequirementMatchingInactiveAutomaticModuleName() throws Exception {
        File primary = primaryModule("embedded.real", "dormant");
        File dependency = newFile("dormant.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            writeDescriptor(output, "embedded.real", new String[0], null);
        }

        ModuleInfoConfiguration.Requirement external = new ModuleInfoConfiguration.Requirement();
        external.setModule("dormant");
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAdditionalRequires(Collections.singletonList(external));

        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency);
        request.setModuleInfoConfiguration(configuration);

        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            Set<String> requirements = readRequirementAccess(shadedJar).keySet();
            assertTrue(requirements.contains("dormant"));
            assertFalse(requirements.contains("embedded.real"));
        }
    }

    @Test
    public void openPrimaryModuleDoesNotEmitExplicitOpens() throws Exception {
        Assumptions.assumeFalse(System.getProperty("java.specification.version").startsWith("1."));

        File primary = newFile("primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeDescriptor(
                    output,
                    "module-info.class",
                    Opcodes.ACC_OPEN,
                    "app.module",
                    new String[] {"dep.auto"},
                    null,
                    "app/api");
            writeClass(output, "app/api/App");
        }
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), automaticModuleManifest("dep.auto"))) {
            writeClass(output, "dep/internal/Dependency");
        }

        ModuleInfoConfiguration.PackageDirective additionalOpen = new ModuleInfoConfiguration.PackageDirective();
        additionalOpen.setPackageName("app.api");
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setPublicBoundary("merge");
        configuration.setAdditionalOpens(Collections.singletonList(additionalOpen));

        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency);
        request.setModuleInfoConfiguration(configuration);
        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertTrue((readModuleAccess(shadedJar) & Opcodes.ACC_OPEN) != 0);
            assertTrue(readPackages(shadedJar, Directive.OPEN).isEmpty());
        }
        assertModuleFinderAccepts(shadedFile);
    }

    @Test
    public void embeddedOpenModuleContributesAllPackagesAsOpens() throws Exception {
        File primary = primaryModule("embedded.open");
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency.toPath()))) {
            writeDescriptor(output, "module-info.class", Opcodes.ACC_OPEN, "embedded.open", new String[0], null);
            writeClass(output, "dep/api/PublicType");
            writeClass(output, "dep/internal/HiddenType");
        }

        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setPublicBoundary("merge");

        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency);
        request.setModuleInfoConfiguration(configuration);
        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            assertEquals(
                    new LinkedHashSet<>(Arrays.asList("dep/api", "dep/internal")),
                    readPackages(shadedJar, Directive.OPEN));
        }
    }

    @Test
    public void analyzesClassesWhenEmbeddedDescriptorIsFiltered() throws Exception {
        Assumptions.assumeFalse(System.getProperty("java.specification.version").startsWith("1."));

        File primary = primaryModule("dep.module");
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), automaticModuleManifest("dep.auto"))) {
            writeDescriptor(output, "dep.module", new String[0], null);
            writeClassReferencing(output, "dep/Dependency", "java/sql/Driver");
        }

        Filter filter = mock(Filter.class);
        when(filter.canFilter(dependency)).thenReturn(true);
        when(filter.isFiltered("module-info.class")).thenReturn(true);

        File shadedFile = newFile("shaded.jar");
        ShadeRequest request = moduleRequest(primary, shadedFile, dependency);
        request.setFilters(Collections.singletonList(filter));
        shade(request);

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            Set<String> requirements = readRequirementAccess(shadedJar).keySet();
            assertTrue(requirements.contains("java.sql"));
            assertFalse(requirements.contains("dep.module"));
        }
    }

    @Test
    public void analyzesClassesWhenVersionedDescriptorIsNotMultiRelease() throws Exception {
        Assumptions.assumeFalse(System.getProperty("java.specification.version").startsWith("1."));

        File primary = primaryModule("dep.auto");
        File dependency = newFile("dependency.jar");
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(dependency.toPath()), automaticModuleManifest("dep.auto"))) {
            writeDescriptor(output, "META-INF/versions/9/module-info.class", "inactive.module", new String[0], null);
            writeClassReferencing(output, "dep/Dependency", "java/sql/Driver");
        }

        File shadedFile = newFile("shaded.jar");
        shade(moduleRequest(primary, shadedFile, dependency));

        try (JarFile shadedJar = new JarFile(shadedFile)) {
            Set<String> requirements = readRequirementAccess(shadedJar).keySet();
            assertTrue(requirements.contains("java.sql"));
            assertFalse(requirements.contains("dep.auto"));
        }
    }

    private File primaryModule(String... dependencies) throws IOException {
        File primary = newFile("primary.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(primary.toPath()))) {
            writeDescriptor(output, "app.module", dependencies, null, "app/api");
            writeClass(output, "app/api/App");
        }
        return primary;
    }

    private File newFile(String name) throws IOException {
        Path file = temporaryFolder.toPath().resolve(name);
        Files.createFile(file);
        return file.toFile();
    }

    private ShadeRequest moduleRequest(File primary, File output, File... dependencies) {
        Set<File> jars = new LinkedHashSet<>();
        jars.add(primary);
        jars.addAll(Arrays.asList(dependencies));
        ShadeRequest request = new ShadeRequest();
        request.setJars(jars);
        request.setPrimaryArtifact(primary);
        request.setModuleInfoMode(ModuleInfoMode.MERGE);
        request.setFilters(Collections.emptyList());
        request.setRelocators(Collections.emptyList());
        request.setResourceTransformers(Collections.emptyList());
        request.setDependencyAnalysisArtifacts(Collections.emptySet());
        request.setUberJar(output);
        return request;
    }

    private void shade(ShadeRequest request) throws Exception {
        new DefaultShader(mock(Logger.class)).shade(request);
    }

    private static void writeDescriptor(
            JarOutputStream output, String moduleName, String[] requires, String transitive, String... exports)
            throws IOException {
        writeDescriptor(output, "module-info.class", moduleName, requires, transitive, exports);
    }

    private static void writeDescriptor(
            JarOutputStream output,
            String entryName,
            String moduleName,
            String[] requires,
            String transitive,
            String... exports)
            throws IOException {
        writeDescriptor(output, entryName, 0, moduleName, requires, transitive, exports);
    }

    private static void writeDescriptor(
            JarOutputStream output,
            String entryName,
            int moduleAccess,
            String moduleName,
            String[] requires,
            String transitive,
            String... exports)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule(moduleName, moduleAccess, null);
        module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        for (String requirement : requires) {
            module.visitRequire(requirement, 0, null);
        }
        if (transitive != null) {
            module.visitRequire(transitive, Opcodes.ACC_TRANSITIVE, null);
        }
        for (String packaze : exports) {
            module.visitExport(packaze, 0);
            module.visitPackage(packaze);
        }
        module.visitEnd();
        writer.visitEnd();
        writeEntry(output, entryName, writer.toByteArray());
    }

    private static void writeClass(JarOutputStream output, String name) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        writeEntry(output, name + ".class", writer.toByteArray());
    }

    private static void writeClassReferencing(JarOutputStream output, String name, String referencedClass)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "dependency", 'L' + referencedClass + ';', null, null)
                .visitEnd();
        writer.visitEnd();
        writeEntry(output, name + ".class", writer.toByteArray());
    }

    private static void writeDynamicServiceLoaderConsumer(JarOutputStream output, String name) throws IOException {
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
        writeEntry(output, name + ".class", writer.toByteArray());
    }

    private static Manifest automaticModuleManifest(String name) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", name);
        return manifest;
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static Set<String> readPackages(JarFile jar, Directive directive) throws IOException {
        Set<String> packages = new LinkedHashSet<>();
        readModule(jar, new ModuleVisitor(Opcodes.ASM9) {
            @Override
            public void visitExport(String packaze, int access, String... modules) {
                if (directive == Directive.EXPORT) {
                    packages.add(packaze);
                }
            }

            @Override
            public void visitOpen(String packaze, int access, String... modules) {
                if (directive == Directive.OPEN) {
                    packages.add(packaze);
                }
            }
        });
        return packages;
    }

    private static int readModuleAccess(JarFile jar) throws IOException {
        int[] moduleAccess = new int[1];
        readModule(jar, new ModuleVisitor(Opcodes.ASM9) {}, moduleAccess);
        return moduleAccess[0];
    }

    private static void assertModuleFinderAccepts(File jar) throws Exception {
        Class<?> moduleFinder = Class.forName("java.lang.module.ModuleFinder");
        Object finder = moduleFinder.getMethod("of", Path[].class).invoke(null, (Object) new Path[] {jar.toPath()});
        moduleFinder.getMethod("findAll").invoke(finder);
    }

    private static Set<String> readUses(JarFile jar) throws IOException {
        Set<String> uses = new LinkedHashSet<>();
        readModule(jar, new ModuleVisitor(Opcodes.ASM9) {
            @Override
            public void visitUse(String service) {
                uses.add(service);
            }
        });
        return uses;
    }

    private static Map<String, Integer> readRequirementAccess(JarFile jar) throws IOException {
        return readRequirementAccess(jar, "app.module");
    }

    private static Map<String, Integer> readRequirementAccess(JarFile jar, String expectedModuleName)
            throws IOException {
        Map<String, Integer> requirements = new HashMap<>();
        readModule(jar, expectedModuleName, new ModuleVisitor(Opcodes.ASM9) {
            @Override
            public void visitRequire(String module, int access, String version) {
                requirements.put(module, access);
            }
        });
        return requirements;
    }

    private static void readModule(JarFile jar, ModuleVisitor visitor) throws IOException {
        readModule(jar, "app.module", visitor, null);
    }

    private static void readModule(JarFile jar, ModuleVisitor visitor, int[] moduleAccess) throws IOException {
        readModule(jar, "app.module", visitor, moduleAccess);
    }

    private static void readModule(JarFile jar, String expectedModuleName, ModuleVisitor visitor) throws IOException {
        readModule(jar, expectedModuleName, visitor, null);
    }

    private static void readModule(JarFile jar, String expectedModuleName, ModuleVisitor visitor, int[] moduleAccess)
            throws IOException {
        JarEntry entry = requireNonNull(jar.getJarEntry("module-info.class"), "module-info.class in " + jar.getName());
        try (InputStream input = jar.getInputStream(entry)) {
            new ClassReader(input)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    assertEquals(expectedModuleName, name);
                                    if (moduleAccess != null) {
                                        moduleAccess[0] = access;
                                    }
                                    return visitor;
                                }
                            },
                            0);
        }
    }

    private static String readModuleName(JarFile jar) throws IOException {
        String[] moduleName = new String[1];
        JarEntry entry = requireNonNull(jar.getJarEntry("module-info.class"), "module-info.class in " + jar.getName());
        try (InputStream input = jar.getInputStream(entry)) {
            new ClassReader(input)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public ModuleVisitor visitModule(String name, int access, String version) {
                                    moduleName[0] = name;
                                    return null;
                                }
                            },
                            0);
        }
        return moduleName[0];
    }

    private enum Directive {
        EXPORT,
        OPEN
    }
}
