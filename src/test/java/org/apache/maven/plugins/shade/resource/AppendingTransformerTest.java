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
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Before
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
    public void testProcessResource() throws IOException {
        transformer.resource = "test-resource";
        String firstLine = "first line";
        String secondLine = "second line";
        InputStream firstIs = new ByteArrayInputStream(firstLine.getBytes());
        InputStream secondIs = new ByteArrayInputStream(secondLine.getBytes());

        transformer.processResource("", firstIs, Collections.emptyList());
        transformer.processResource("", secondIs, Collections.emptyList());

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final JarOutputStream jarOutputStream = new JarOutputStream(out)) {
            transformer.modifyOutputStream(jarOutputStream);
        }

        try (final JarInputStream jis = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals("test-resource", jis.getNextJarEntry().getName());
            String result = read(jis);
            assertEquals(firstLine + "\n" + secondLine, result);
        }
    }

    private String read(final JarInputStream jar) throws IOException {
        final StringBuilder builder = new StringBuilder();
        final byte[] buffer = new byte[512];
        int read;
        while ((read = jar.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read));
        }
        return builder.toString();
    }
}
