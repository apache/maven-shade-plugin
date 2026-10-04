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
import java.nio.file.Files;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlatformModuleIndexTest {
    @TempDir
    File temporaryFolder;

    @Test
    public void indexesHistoricalAndCurrentPlatformOwnership() throws Exception {
        File jdkHome = syntheticJdk();

        PlatformModuleIndex index = new PlatformModuleIndex(jdkHome);

        assertEquals(21, index.getRelease());
        assertEquals(singletonSortedSet("java.sql"), index.findOwners("java/sql/Connection", 9));
        assertEquals(singletonSortedSet("java.xml"), index.findOwners("javax/xml/parsers/Parser", 10));
        assertTrue(index.findOwners("java/sql/Connection", 10).isEmpty());
        assertEquals(singletonSortedSet("java.base"), index.findOwners("java/lang/Object", 21));
        assertTrue(index.hasModule("java.sql", 9));
        assertTrue(index.hasModule("java.xml", 10));
        assertFalse(index.hasModule("java.xml", 9));
        assertTrue(index.hasModule("java.base", 21));
    }

    @Test
    public void rejectsJdkWithoutHistoricalPlatformSignatures() throws Exception {
        File jdkHome = newFolder("jdk");
        File jmods = new File(jdkHome, "jmods");
        assertTrue(jmods.mkdirs());
        writeJmod(new File(jmods, "java.base.jmod"), "java.base", Opcodes.V21, "java/lang/Object");

        MojoExecutionException exception =
                assertThrows(MojoExecutionException.class, () -> new PlatformModuleIndex(jdkHome));

        assertTrue(exception.getMessage().contains("missing lib/ct.sym"));
        assertTrue(exception.getMessage().contains(jdkHome.getAbsolutePath()));
    }

    private File syntheticJdk() throws IOException {
        File jdkHome = newFolder("jdk");
        File jmods = new File(jdkHome, "jmods");
        File lib = new File(jdkHome, "lib");
        assertTrue(jmods.mkdirs());
        assertTrue(lib.mkdirs());

        writeJmod(new File(jmods, "java.base.jmod"), "java.base", Opcodes.V21, "java/lang/Object");
        writeJmod(new File(jmods, "java.sql.jmod"), "java.sql", Opcodes.V21, "java/sql/Connection");

        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(new File(lib, "ct.sym").toPath()))) {
            writeEmptyEntry(output, "9/java.sql/module-info.sig");
            writeEmptyEntry(output, "9/java.sql/java/sql/Connection.sig");
            writeEmptyEntry(output, "A/java.xml/module-info.sig");
            writeEmptyEntry(output, "A/java.xml/javax/xml/parsers/Parser.sig");
        }
        return jdkHome;
    }

    private File newFolder(String name) throws IOException {
        return Files.createDirectory(temporaryFolder.toPath().resolve(name)).toFile();
    }

    private static void writeJmod(File file, String moduleName, int classVersion, String className) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(classVersion, Opcodes.ACC_MODULE, "module-info", null, null, null);
            ModuleVisitor module = writer.visitModule(moduleName, 0, null);
            module.visitEnd();
            writer.visitEnd();
            output.putNextEntry(new JarEntry("classes/module-info.class"));
            output.write(writer.toByteArray());
            output.closeEntry();

            writeEmptyEntry(output, "classes/" + className + ".class");
        }
    }

    private static void writeEmptyEntry(JarOutputStream output, String name) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(new byte[] {0});
        output.closeEntry();
    }

    private static SortedSet<String> singletonSortedSet(String value) {
        return new TreeSet<>(Collections.singleton(value));
    }
}
