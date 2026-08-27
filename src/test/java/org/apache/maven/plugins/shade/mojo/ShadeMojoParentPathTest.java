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
package org.apache.maven.plugins.shade.mojo;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShadeMojoParentPathTest {
    @TempDir
    File temporaryFolder;

    @Test
    public void matchingParentFilesKeepTheRelativePath() throws Exception {
        File pom = new File(temporaryFolder, "pom.xml");
        Files.write(pom.toPath(), "<project/>".getBytes("UTF-8"));
        assertTrue(ShadeMojo.isSameFile(pom, pom));
    }

    @Test
    public void aSiblingPomIsNotTheParent() throws Exception {
        File parentPom = new File(temporaryFolder, "real-parent.xml");
        File siblingPom = new File(temporaryFolder, "pom.xml");
        Files.write(parentPom.toPath(), "<project/>".getBytes("UTF-8"));
        Files.write(siblingPom.toPath(), "<project/>".getBytes("UTF-8"));
        assertFalse(ShadeMojo.isSameFile(siblingPom, parentPom));
    }

    @Test
    public void missingActualParentDropsTheRelativePath() throws Exception {
        File siblingPom = new File(temporaryFolder, "pom.xml");
        Files.write(siblingPom.toPath(), "<project/>".getBytes("UTF-8"));
        assertFalse(ShadeMojo.isSameFile(siblingPom, null));
    }
}
