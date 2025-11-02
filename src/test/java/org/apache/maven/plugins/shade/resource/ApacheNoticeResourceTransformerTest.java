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
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test for {@link ApacheNoticeResourceTransformer}.
 *
 * @author Benjamin Bentmann
 */
public class ApacheNoticeResourceTransformerTest {

    private static final String NOTICE_RESOURCE = "META-INF/NOTICE";
    private ApacheNoticeResourceTransformer transformer;

    static {
        /*
         * NOTE: The Turkish locale has an usual case transformation for the letters "I" and "i", making it a prime
         * choice to test for improper case-less string comparisions.
         */
        Locale.setDefault(new Locale("tr"));
    }

    @BeforeEach
    public void setUp() {
        transformer = new ApacheNoticeResourceTransformer();
    }

    @Test
    public void testCanTransformResource() {
        assertTrue(transformer.canTransformResource("META-INF/NOTICE"));
        assertTrue(transformer.canTransformResource("META-INF/NOTICE.TXT"));
        assertTrue(transformer.canTransformResource("META-INF/NOTICE.md"));
        assertTrue(transformer.canTransformResource("META-INF/Notice.txt"));
        assertTrue(transformer.canTransformResource("META-INF/Notice.md"));
        assertFalse(transformer.canTransformResource("META-INF/MANIFEST.MF"));
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenNoInput() throws IOException {
        processAndFailOnNullPointer("");
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenNoLinesOfInput() throws IOException {
        processAndFailOnNullPointer("Some notice text");
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenOneLineOfInput() throws IOException {
        processAndFailOnNullPointer("Some notice text\n");
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenTwoLinesOfInput() throws IOException {
        processAndFailOnNullPointer("Some notice text\nSome notice text\n");
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenLineStartsWithSlashSlash() throws IOException {
        processAndFailOnNullPointer("Some notice text\n//Some notice text\n");
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenLineIsSlashSlash() throws IOException {
        processAndFailOnNullPointer("//\n");
    }

    @Test
    public void testNoParametersShouldNotThrowNullPointerWhenLineIsEmpty() throws IOException {
        processAndFailOnNullPointer("\n");
    }

    private void processAndFailOnNullPointer(final String noticeText) throws IOException {
        try {
            final ByteArrayInputStream noticeInputStream = new ByteArrayInputStream(noticeText.getBytes());
            final List<Relocator> emptyList = Collections.emptyList();
            transformer.processResource(NOTICE_RESOURCE, noticeInputStream, emptyList, 0);
            noticeInputStream.close();
        } catch (NullPointerException e) {
            fail("Null pointer should not be thrown when no parameters are set.");
        }
    }
}
