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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link SisuIndexResourceTransformer}.
 */
public class SisuIndexResourceTransformerTest {
    private static final String INDEX = "META-INF/sisu/javax.inject.Named";

    private final SisuIndexResourceTransformer transformer = new SisuIndexResourceTransformer();

    @Test
    public void testCanTransformResource() {
        assertTrue(transformer.canTransformResource(INDEX));
        assertFalse(transformer.canTransformResource("META-INF/sisu/other"));
    }

    /**
     * MSHADE-390: the index of every jar ends up in one index, with the class names relocated.
     */
    @Test
    public void testIndexesAreMergedAndRelocated() throws IOException {
        List<Relocator> relocators = Collections.<Relocator>singletonList(new SimpleRelocator(
                "org.apache.one", "org.apache.two.org.apache.one", null, Collections.<String>emptyList()));

        process("org.apache.one.One", relocators);
        process("org.apache.two.Two", relocators);

        assertEquals(
                Arrays.asList("org.apache.two.org.apache.one.One", "org.apache.two.Two"),
                Arrays.asList(writeIndex().split("\n")));
    }

    private void process(String index, List<Relocator> relocators) throws IOException {
        transformer.processResource(
                INDEX, new ByteArrayInputStream(index.getBytes(StandardCharsets.UTF_8)), relocators, 0);
    }

    private String writeIndex() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            transformer.modifyOutputStream(jar);
        }
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            JarEntry entry = in.getNextJarEntry();
            assertEquals(INDEX, entry.getName());
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                content.write(buffer, 0, read);
            }
            return new String(content.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
