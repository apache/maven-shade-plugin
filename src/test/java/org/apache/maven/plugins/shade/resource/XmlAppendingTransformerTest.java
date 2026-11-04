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
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link XmlAppendingTransformer}.
 *
 * @author Benjamin Bentmann
 *
 */
public class XmlAppendingTransformerTest {

    private XmlAppendingTransformer transformer;

    static {
        /*
         * NOTE: The Turkish locale has an usual case transformation for the letters "I" and "i", making it a prime
         * choice to test for improper case-less string comparisions.
         */
        Locale.setDefault(new Locale("tr"));
    }

    @BeforeEach
    public void setUp() {
        transformer = new XmlAppendingTransformer();
    }

    @Test
    public void testCanTransformResource() {
        transformer.resource = "abcdefghijklmnopqrstuvwxyz";

        assertTrue(transformer.canTransformResource("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(transformer.canTransformResource("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(transformer.canTransformResource("META-INF/MANIFEST.MF"));
    }

    /**
     * The DOCTYPE of this document points at a DTD that does not exist: the transformer must not try to load it.
     */
    @Test
    public void testDtdIsNotResolved() throws IOException {
        transformer.resource = "test.xml";
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"xhtml1-invalid.missing\">\n"
                + "<html>\n  <!-- the DTD would fail the parsing if it was resolved -->\n</html>\n";

        transformer.processResource(
                "test.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.ISO_8859_1)),
                Collections.<Relocator>emptyList(),
                0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            transformer.modifyOutputStream(jar);
        }
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            JarEntry entry = in.getNextJarEntry();
            assertEquals("test.xml", entry.getName());
        }
    }
}
