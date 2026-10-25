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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link AppendingTransformer}.
 *
 * @author Benjamin Bentmann
 */
public class AppendingTransformerTest {

    private AppendingTransformer transformer;

    static {
        /*
         * NOTE: The Turkish locale has an usual case transformation for the letters "I" and "i", making it a prime
         * choice to test for improper case-less string comparisions.
         */
        Locale.setDefault(new Locale("tr"));
    }

    @BeforeEach
    public void setUp() {
        transformer = new AppendingTransformer();
    }

    @Test
    public void testCanTransformResource() {
        transformer.resource = "abcdefghijklmnopqrstuvwxyz";

        assertTrue(transformer.canTransformResource("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(transformer.canTransformResource("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(transformer.canTransformResource("META-INF/MANIFEST.MF"));
    }

    @Test
    public void testProcessResourceDoesNotAddAnExtraLineFeed() throws Exception {
        transformer.processResource(
                "META-INF/services/example",
                new ByteArrayInputStream("first\n".getBytes(StandardCharsets.UTF_8)),
                Collections.emptyList(),
                0);
        transformer.processResource(
                "META-INF/services/example",
                new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)),
                Collections.emptyList(),
                0);

        assertArrayEquals("first\nsecond\n".getBytes(StandardCharsets.UTF_8), transformer.data.toByteArray());
    }

    @Test
    public void testProcessEmptyResourcesPreservesAnEmptyEntry() throws Exception {
        transformer.resource = "META-INF/services/example";
        transformer.processResource(
                transformer.resource, new ByteArrayInputStream(new byte[0]), Collections.emptyList(), 0);
        transformer.processResource(
                transformer.resource, new ByteArrayInputStream(new byte[0]), Collections.emptyList(), 0);

        assertTrue(transformer.hasTransformedResource());

        ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
        try (JarOutputStream jarOutput = new JarOutputStream(jarBytes)) {
            transformer.modifyOutputStream(jarOutput);
        }

        try (JarInputStream jarInput = new JarInputStream(new ByteArrayInputStream(jarBytes.toByteArray()))) {
            JarEntry entry = jarInput.getNextJarEntry();
            assertNotNull(entry);
            assertEquals("META-INF/services/example", entry.getName());
            assertEquals(-1, jarInput.read());
            assertNull(jarInput.getNextJarEntry());
        }
    }
}
