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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link IncludeResourceTransformer}.
 */
class IncludeResourceTransformerTest {

    @TempDir
    Path tempDir;

    @Test
    void includesTheFile() throws IOException {
        Path file = Files.write(tempDir.resolve("LICENSE"), "license text".getBytes(StandardCharsets.UTF_8));
        IncludeResourceTransformer transformer = new IncludeResourceTransformer();
        transformer.file = file.toFile();
        transformer.resource = "META-INF/LICENSE";

        assertTrue(transformer.hasTransformedResource());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bytes)) {
            transformer.modifyOutputStream(jos);
        }

        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals("META-INF/LICENSE", jis.getNextJarEntry().getName());
        }
    }

    @Test
    void failsWhenTheFileIsMissing() {
        File missing = tempDir.resolve("no-such-file").toFile();
        IncludeResourceTransformer transformer = new IncludeResourceTransformer();
        transformer.file = missing;
        transformer.resource = "META-INF/LICENSE";

        assertTrue(transformer.hasTransformedResource(), "a configured file must not be skipped silently");
        IOException e = assertThrows(
                IOException.class,
                () -> transformer.modifyOutputStream(new JarOutputStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().contains(missing.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("META-INF/LICENSE"), e.getMessage());
    }
}
