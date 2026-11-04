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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SerializedLambdaRelocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Shades small jars made from the classes in {@code relocation.fixture} and checks what the relocated classes do
 * when they are loaded from the shaded jar, which is what the relocation of annotations, string constants,
 * resource paths and serialized lambdas has to preserve.
 */
public class ShadedRelocationTest {
    private static final String FIXTURE = "org.apache.maven.plugins.shade.relocation.fixture";

    @TempDir
    File temp;

    @Test
    public void relocatedAnnotationIsStillPresent() throws Exception {
        File jar = shade(
                relocators(new SimpleRelocator(FIXTURE + ".anno.relocated", "hidden", null, null)),
                FIXTURE + ".anno.Main",
                FIXTURE + ".anno.relocated.MyAnno");

        // Main.main() throws when an annotation is missing from the class, a field, a method or a constructor
        runMain(jar, FIXTURE + ".anno.Main");
    }

    @Test
    public void classNamesInStringConstantsAreRelocated() throws Exception {
        File jar = shade(
                relocators(new SimpleRelocator(FIXTURE + ".stringpool.relocated", "hidden", null, null)),
                FIXTURE + ".stringpool.Main",
                FIXTURE + ".stringpool.relocated.RelocatedClass");

        // Main.main() loads the relocated class by a name it has as a constant: with slashes, dots, as an array
        runMain(jar, FIXTURE + ".stringpool.Main");
    }

    @Test
    public void enumReferencedFromAnnotationIsRelocated() throws Exception {
        File jar = shade(
                relocators(new SimpleRelocator(FIXTURE + ".enumanno.relocated", "hidden", null, null)),
                FIXTURE + ".enumanno.Main",
                FIXTURE + ".enumanno.MyAnno",
                FIXTURE + ".enumanno.relocated.MyEnum");

        runMain(jar, FIXTURE + ".enumanno.Main");
    }

    @Test
    public void absoluteResourcePathIsRelocated() throws Exception {
        File jar = shade(
                relocators(new SimpleRelocator("org.apache.maven.from", "org.apache.maven.to", null, null)),
                Collections.singletonMap(
                        "org/apache/maven/from/test.properties",
                        resourceFile("abspath/org/apache/maven/from/test.properties")),
                FIXTURE + ".abspath.Main");

        // Main.main() fails with a NullPointerException when the resource is not found at the path it asks for
        runMain(jar, FIXTURE + ".abspath.Main");
        try (JarFile shaded = new JarFile(jar)) {
            assertNotNull(shaded.getEntry("org/apache/maven/to/test.properties"));
            assertNull(shaded.getEntry("org/apache/maven/from/test.properties"));
        }
    }

    @Test
    public void excludedAbsoluteResourcePathIsNotRelocated() throws Exception {
        Map<String, File> resources = new LinkedHashMap<>();
        resources.put(
                "org/apache/maven/from/a/test.properties",
                resourceFile("abspathexclude/org/apache/maven/from/a/test.properties"));
        resources.put(
                "org/apache/maven/from/b/test.properties",
                resourceFile("abspathexclude/org/apache/maven/from/b/test.properties"));
        File jar = shade(
                relocators(new SimpleRelocator(
                        "org.apache.maven.from",
                        "org.apache.maven.to",
                        null,
                        Collections.singletonList("org.apache.maven.from.a.*"))),
                resources,
                FIXTURE + ".abspathexclude.Main");

        // Main.main() fails when a/ was relocated although excluded, or when b/ was not relocated
        runMain(jar, FIXTURE + ".abspathexclude.Main");
        try (JarFile shaded = new JarFile(jar)) {
            assertNotNull(shaded.getEntry("org/apache/maven/from/a/test.properties"));
            assertNotNull(shaded.getEntry("org/apache/maven/to/b/test.properties"));
        }
    }

    /**
     * MSHADE-260: a serialized lambda is read back through the relocated names it was written with.
     */
    @Test
    public void serializedLambdaSurvivesRelocation() throws Exception {
        String pattern = FIXTURE + ".lambda";
        String shadedPattern = "org.apache.maven.plugins.shade.shaded.lambda";
        File jar = shade(
                relocators(
                        new SimpleRelocator(pattern, shadedPattern, null, null),
                        new SerializedLambdaRelocator(pattern, shadedPattern, null, null, false)),
                FIXTURE + ".lambda.Main",
                FIXTURE + ".lambda.MapFunction",
                FIXTURE + ".lambda.DataHolder",
                FIXTURE + ".lambda.Processor");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, null)) {
            Class<?> main = loader.loadClass(shadedPattern + ".Main");
            Object mapper = main.getMethod("mapper").invoke(null);
            Object data = loader.loadClass(shadedPattern + ".DataHolder")
                    .getConstructor(String.class)
                    .newInstance("test");

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(mapper);
            }
            Object copy;
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                    return Class.forName(desc.getName(), false, loader);
                }
            }) {
                copy = in.readObject();
            }

            Method map = loader.loadClass(shadedPattern + ".MapFunction").getMethod("map", Object.class);
            map.setAccessible(true);
            assertEquals("Processed: test", map.invoke(copy, data));
        }
    }

    /**
     * Includes and excludes of two relocations apply to the classes of one jar independently.
     */
    @Test
    public void relocationsHonourIncludesAndExcludes() throws Exception {
        File input = new File(temp, "junit.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input.toPath()))) {
            for (String name : new String[] {
                "junit/textui/TestRunner",
                "junit/textui/ResultPrinter",
                "junit/framework/Assert",
                "junit/framework/Test",
                "junit/framework/TestCase"
            }) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
                writer.visitEnd();
                jar.putNextEntry(new JarEntry(name + ".class"));
                jar.write(writer.toByteArray());
            }
        }
        File output = new File(temp, "shaded-junit.jar");
        ShadeRequest request = new ShadeRequest();
        request.setJars(Collections.singleton(input));
        request.setUberJar(output);
        request.setFilters(Collections.<Filter>emptyList());
        request.setRelocators(relocators(
                new SimpleRelocator("junit.textui", "a", null, Collections.singletonList("junit.textui.TestRunner")),
                new SimpleRelocator("junit.framework", "b", Collections.singletonList("junit.framework.Test*"), null)));
        request.setResourceTransformers(Collections.<ResourceTransformer>emptyList());
        new DefaultShader(mock(Logger.class)).shade(request);

        Set<String> entries = new TreeSet<>();
        try (JarFile jar = new JarFile(output)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (entry.getName().endsWith(".class")) {
                    entries.add(entry.getName());
                }
            }
        }
        assertEquals(
                new TreeSet<>(Arrays.asList(
                        "a/ResultPrinter.class",
                        "junit/textui/TestRunner.class",
                        "b/Test.class",
                        "b/TestCase.class",
                        "junit/framework/Assert.class")),
                entries);
    }

    private static List<Relocator> relocators(Relocator... relocators) {
        List<Relocator> list = new ArrayList<>();
        Collections.addAll(list, relocators);
        return list;
    }

    private File shade(List<Relocator> relocators, String... classes) throws Exception {
        return shade(relocators, Collections.<String, File>emptyMap(), classes);
    }

    private File shade(List<Relocator> relocators, Map<String, File> resources, String... classes) throws Exception {
        File input = new File(temp, "input.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input.toPath()))) {
            for (String className : classes) {
                String entry = className.replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(entry));
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(entry)) {
                    assertNotNull(in, entry);
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        jar.write(buffer, 0, read);
                    }
                }
            }
            for (Map.Entry<String, File> resource : resources.entrySet()) {
                jar.putNextEntry(new JarEntry(resource.getKey()));
                jar.write(Files.readAllBytes(resource.getValue().toPath()));
            }
        }

        File output = new File(temp, "shaded.jar");
        Set<File> jars = new TreeSet<>();
        jars.add(input);
        ShadeRequest request = new ShadeRequest();
        request.setJars(jars);
        request.setUberJar(output);
        request.setFilters(Collections.<Filter>emptyList());
        request.setRelocators(relocators);
        request.setResourceTransformers(Collections.<ResourceTransformer>emptyList());
        new DefaultShader(mock(Logger.class)).shade(request);
        return output;
    }

    private File resourceFile(String name) throws IOException {
        URL url = getClass().getClassLoader().getResource("relocation-fixture/" + name);
        assertNotNull(url, name);
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private void runMain(File jar, String mainClass) throws Exception {
        // no parent loader: only what is in the shaded jar and the JDK can be found
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, null)) {
            Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception) {
                    throw (Exception) e.getCause();
                }
                throw e;
            }
        }
    }
}
