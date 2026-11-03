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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.objectweb.asm.ClassReader;

/**
 * Release-aware ownership index for classes in a selected JDK.
 */
final class PlatformModuleIndex {
    private static final int FIRST_MODULE_RELEASE = 9;

    private final File jdkHome;

    private final Map<Integer, Map<String, SortedSet<String>>> classOwners = new HashMap<>();

    private final Map<Integer, Set<String>> modules = new HashMap<>();

    private final int release;

    PlatformModuleIndex(File jdkHome) throws MojoExecutionException {
        this.jdkHome = normalizeJdkHome(jdkHome);
        this.release = readCurrentRelease();
        readHistoricalReleases();
        readCurrentReleaseClasses();
    }

    int getRelease() {
        return release;
    }

    SortedSet<String> findOwners(String className, int targetRelease) {
        Map<String, SortedSet<String>> releaseOwners = classOwners.get(targetRelease);
        if (releaseOwners == null) {
            return new TreeSet<>();
        }
        Set<String> owners = releaseOwners.get(className);
        return owners == null ? new TreeSet<String>() : new TreeSet<>(owners);
    }

    boolean hasModule(String module, int targetRelease) {
        return modules.getOrDefault(targetRelease, Collections.<String>emptySet())
                .contains(module);
    }

    private int readCurrentRelease() throws MojoExecutionException {
        File javaBase = new File(new File(jdkHome, "jmods"), "java.base.jmod");
        if (!javaBase.isFile()) {
            throw invalidJdk("missing jmods/java.base.jmod");
        }
        try (JarFile jmod = new JarFile(javaBase)) {
            JarEntry descriptor = jmod.getJarEntry("classes/module-info.class");
            if (descriptor == null) {
                throw invalidJdk("jmods/java.base.jmod has no module descriptor");
            }
            try (InputStream input = jmod.getInputStream(descriptor)) {
                return classRelease(new ClassReader(input).readShort(6));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Cannot read the module-info analysis JDK at " + jdkHome, e);
        }
    }

    private void readHistoricalReleases() throws MojoExecutionException {
        File ctSym = new File(new File(jdkHome, "lib"), "ct.sym");
        if (!ctSym.isFile()) {
            if (release > FIRST_MODULE_RELEASE) {
                throw invalidJdk("missing lib/ct.sym");
            }
            return;
        }
        try (JarFile archive = new JarFile(ctSym)) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String[] parts = entry.getName().split("/", 3);
                if (parts.length < 3) {
                    continue;
                }
                String releaseSet = parts[0];
                String module = parts[1];
                String path = parts[2];
                if ("module-info.sig".equals(path)) {
                    forEachRelease(
                            releaseSet,
                            value -> modules.computeIfAbsent(value, key -> new TreeSet<>())
                                    .add(module));
                } else if (path.endsWith(".sig")) {
                    String className = path.substring(0, path.length() - ".sig".length());
                    forEachRelease(releaseSet, value -> addOwner(value, className, module));
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + ctSym + " for module-info analysis", e);
        }
    }

    private void readCurrentReleaseClasses() throws MojoExecutionException {
        File jmods = new File(jdkHome, "jmods");
        File[] files = jmods.listFiles((directory, name) -> name.endsWith(".jmod"));
        if (files == null || files.length == 0) {
            throw invalidJdk("the jmods directory is empty");
        }
        for (File file : files) {
            String module = file.getName().substring(0, file.getName().length() - ".jmod".length());
            modules.computeIfAbsent(release, key -> new TreeSet<>()).add(module);
            try (JarFile jmod = new JarFile(file)) {
                Enumeration<JarEntry> entries = jmod.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory()
                            && name.startsWith("classes/")
                            && name.endsWith(".class")
                            && !"classes/module-info.class".equals(name)) {
                        addOwner(
                                release,
                                name.substring("classes/".length(), name.length() - ".class".length()),
                                module);
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot read " + file + " for module-info analysis", e);
            }
        }
    }

    private void forEachRelease(String releaseSet, ReleaseConsumer consumer) {
        for (int index = 0; index < releaseSet.length(); index++) {
            int value = Character.digit(releaseSet.charAt(index), 36);
            if (value >= FIRST_MODULE_RELEASE && value < release) {
                consumer.accept(value);
            }
        }
    }

    private void addOwner(int targetRelease, String className, String module) {
        classOwners
                .computeIfAbsent(targetRelease, key -> new HashMap<>())
                .computeIfAbsent(className, key -> new TreeSet<>())
                .add(module);
        modules.computeIfAbsent(targetRelease, key -> new TreeSet<>()).add(module);
    }

    private MojoExecutionException invalidJdk(String reason) {
        return new MojoExecutionException("Invalid module-info analysis JDK at " + jdkHome + ": " + reason
                + ". Configure moduleInfo.analysisJdkToolchain with a complete modular JDK.");
    }

    private static File normalizeJdkHome(File configured) {
        File home = configured == null ? new File(System.getProperty("java.home")) : configured;
        if (!new File(home, "jmods").isDirectory()
                && home.getParentFile() != null
                && new File(home.getParentFile(), "jmods").isDirectory()) {
            return home.getParentFile();
        }
        return home;
    }

    private static int classRelease(int classVersion) {
        return (classVersion & 0xFFFF) - 44;
    }

    private interface ReleaseConsumer {
        void accept(int release);
    }
}
