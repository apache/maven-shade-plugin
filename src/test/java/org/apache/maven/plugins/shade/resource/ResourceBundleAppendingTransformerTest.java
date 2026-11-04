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
package org.apache.maven.plugins.shade.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceBundleAppendingTransformerTest {
    private ResourceBundleAppendingTransformer transformer;

    @BeforeEach
    public void setUp() {
        transformer = new ResourceBundleAppendingTransformer();
    }

    @Test
    public void testCanTransformResource() {
        transformer.setBasename("a/b/c/ButtonLabel");

        assertTrue(transformer.canTransformResource("a/b/c/ButtonLabel.properties"));
        assertTrue(transformer.canTransformResource("a/b/c/ButtonLabel_en.properties"));
        assertTrue(transformer.canTransformResource("a/b/c/ButtonLabel_en_US.properties"));
        assertTrue(transformer.canTransformResource("a/b/c/ButtonLabel_fr.properties"));
        assertTrue(transformer.canTransformResource("a/b/c/ButtonLabel_fr_CA.properties"));
        assertTrue(transformer.canTransformResource("a/b/c/ButtonLabel_fr_CA_UNIX.properties"));

        assertFalse(transformer.canTransformResource("a/b/c/ButtonLabel.class"));
        assertFalse(transformer.canTransformResource("c/ButtonLabel.properties"));
        assertFalse(transformer.canTransformResource("ButtonLabel.properties"));
    }

    /**
     * MSHADE-232: every locale variant of a bundle keeps the entries of all jars that provide it.
     */
    @Test
    public void testBundlesOfSeveralJarsAreMergedPerLocale() throws IOException {
        transformer.setBasename("org/apache/maven/plugins/shade/its/Message");
        String base = "org/apache/maven/plugins/shade/its/Message.properties";
        String dutch = "org/apache/maven/plugins/shade/its/Message_nl.properties";

        process(base, "one=one");
        process(dutch, "one=een");
        process(base, "two=two");
        process(dutch, "two=twee");

        JarEntryContents jar = writeJar();
        Properties merged = jar.properties(base);
        assertEquals("one", merged.getProperty("one"));
        assertEquals("two", merged.getProperty("two"));
        Properties mergedDutch = jar.properties(dutch);
        assertEquals("een", mergedDutch.getProperty("one"));
        assertEquals("twee", mergedDutch.getProperty("two"));
    }

    private void process(String resource, String content) throws IOException {
        assertTrue(transformer.canTransformResource(resource), resource);
        transformer.processResource(
                resource,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)),
                Collections.<Relocator>emptyList(),
                0);
    }

    private JarEntryContents writeJar() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            transformer.modifyOutputStream(jar);
        }
        return new JarEntryContents(out.toByteArray());
    }

    private static final class JarEntryContents {
        private final byte[] jar;

        JarEntryContents(byte[] jar) {
            this.jar = jar;
        }

        Properties properties(String name) throws IOException {
            try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
                JarEntry entry;
                while ((entry = in.getNextJarEntry()) != null) {
                    if (entry.getName().equals(name)) {
                        Properties properties = new Properties();
                        properties.load(in);
                        return properties;
                    }
                }
            }
            throw new AssertionError(name + " is not in the merged jar");
        }
    }
}
