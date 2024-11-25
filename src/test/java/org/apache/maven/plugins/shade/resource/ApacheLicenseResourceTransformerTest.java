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

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test for {@link ApacheLicenseResourceTransformer}.
 *
 * @author Benjamin Bentmann
 */
public class ApacheLicenseResourceTransformerTest {

    private ApacheLicenseResourceTransformer transformer;

    static {
        /*
         * NOTE: The Turkish locale has an usual case transformation for the letters "I" and "i", making it a prime
         * choice to test for improper case-less string comparisions.
         */
        Locale.setDefault(new Locale("tr"));
    }

    @Before
    public void setUp() {
        transformer = new ApacheLicenseResourceTransformer();
    }

    @Test
    public void testCanTransformResource() {
        assertTrue(transformer.canTransformResource("META-INF/LICENSE"));
        assertTrue(transformer.canTransformResource("META-INF/LICENSE.TXT"));
        assertTrue(transformer.canTransformResource("META-INF/LICENSE.md"));
        assertTrue(transformer.canTransformResource("META-INF/License.txt"));
        assertTrue(transformer.canTransformResource("META-INF/License.md"));
        assertFalse(transformer.canTransformResource("META-INF/MANIFEST.MF"));
    }
}
