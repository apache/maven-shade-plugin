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
package org.apache.maven.plugins.shade.pom;

import java.io.StringWriter;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PomWriterRelativePathTest {
    @Test
    public void emptyRelativePathIsWritten() throws Exception {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("g");
        model.setArtifactId("a");
        model.setVersion("1.0");
        Parent parent = new Parent();
        parent.setGroupId("pg");
        parent.setArtifactId("pa");
        parent.setVersion("1.0");
        parent.setRelativePath("");
        model.setParent(parent);

        StringWriter writer = new StringWriter();
        PomWriter.write(writer, model, true);
        String xml = writer.toString();

        assertTrue(xml.contains("<relativePath"), xml);
        assertFalse(xml.contains("<relativePath>../pom.xml</relativePath>"), xml);
    }

    @Test
    public void defaultRelativePathIsOmitted() throws Exception {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("g");
        model.setArtifactId("a");
        model.setVersion("1.0");
        Parent parent = new Parent();
        parent.setGroupId("pg");
        parent.setArtifactId("pa");
        parent.setVersion("1.0");
        model.setParent(parent);

        StringWriter writer = new StringWriter();
        PomWriter.write(writer, model, true);
        String xml = writer.toString();

        assertFalse(xml.contains("relativePath"), xml);
    }
}
