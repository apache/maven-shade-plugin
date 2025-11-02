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
package org.apache.maven.plugins.shade.resource.properties;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.apache.maven.plugins.shade.resource.properties.io.NoCloseOutputStream;
import org.apache.maven.plugins.shade.resource.properties.io.SkipPropertiesDateLineWriter;
import org.apache.maven.plugins.shade.resource.rule.TransformerTesterRule;
import org.apache.maven.plugins.shade.resource.rule.TransformerTesterRule.Property;
import org.apache.maven.plugins.shade.resource.rule.TransformerTesterRule.Resource;
import org.apache.maven.plugins.shade.resource.rule.TransformerTesterRule.TransformerTest;
import org.junit.Rule;
import org.junit.jupiter.api.Test;
import org.junit.rules.TestRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PropertiesTransformerTest {
    @Rule
    public final TestRule tester = new TransformerTesterRule();

    @Test
    public void propertiesRewritingIsStable() throws IOException {
        final Properties properties = new SortedProperties();
        properties.setProperty("a", "1");
        properties.setProperty("b", "2");

        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final BufferedWriter writer = new SkipPropertiesDateLineWriter(
                new OutputStreamWriter(new NoCloseOutputStream(os), StandardCharsets.ISO_8859_1));
        properties.store(writer, " Merged by maven-shade-plugin");
        writer.close();
        os.close();

        assertEquals(
                "# Merged by maven-shade-plugin\n" + "a=1\n" + "b=2\n",
                os.toString("UTF-8").replace(System.lineSeparator(), "\n"));
    }

    @Test
    public void canTransform() {
        final PropertiesTransformer transformer = new PropertiesTransformer();
        transformer.setResource("foo/bar/my.properties");
        assertTrue(transformer.canTransformResource("foo/bar/my.properties"));
        assertFalse(transformer.canTransformResource("whatever"));
    }

    @Test
    @TransformerTest(
            transformer = PropertiesTransformer.class,
            configuration = @Property(name = "resource", value = "foo/bar/my.properties"),
            visited = {
                @Resource(path = "foo/bar/my.properties", content = "a=b"),
                @Resource(path = "foo/bar/my.properties", content = "c=d"),
            },
            expected = @Resource(path = "foo/bar/my.properties", content = "#.*\na=b\nc=d\n"))
    public void mergeWithoutOverlap() {}

    @Test
    @TransformerTest(
            transformer = PropertiesTransformer.class,
            configuration = {
                @Property(name = "resource", value = "foo/bar/my.properties"),
                @Property(name = "ordinalKey", value = "priority")
            },
            visited = {
                @Resource(path = "foo/bar/my.properties", content = "a=d\npriority=3"),
                @Resource(path = "foo/bar/my.properties", content = "a=b\npriority=1"),
                @Resource(path = "foo/bar/my.properties", content = "a=c\npriority=2"),
            },
            expected = @Resource(path = "foo/bar/my.properties", content = "#.*\na=d\n"))
    public void mergeWithOverlap() {}

    @Test
    @TransformerTest(
            transformer = PropertiesTransformer.class,
            configuration = {
                @Property(name = "resource", value = "foo/bar/my.properties"),
                @Property(name = "alreadyMergedKey", value = "complete")
            },
            visited = {
                @Resource(path = "foo/bar/my.properties", content = "a=b\ncomplete=true"),
                @Resource(path = "foo/bar/my.properties", content = "a=c\npriority=2"),
            },
            expected = @Resource(path = "foo/bar/my.properties", content = "#.*\na=b\n"))
    public void mergeWithAlreadyMerged() {}

    @Test
    @TransformerTest(
            transformer = PropertiesTransformer.class,
            configuration = {
                @Property(name = "resource", value = "foo/bar/my.properties"),
                @Property(name = "alreadyMergedKey", value = "complete")
            },
            visited = {
                @Resource(path = "foo/bar/my.properties", content = "a=b\ncomplete=true"),
                @Resource(path = "foo/bar/my.properties", content = "a=c\ncomplete=true"),
            },
            expected = {},
            expectedException = IllegalStateException.class)
    public void alreadyMergeConflict() {}
}
