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

// Verify the dependency-reduced POM was created in the expected location
File drpFile = new File(basedir, "target/dependency-reduced-pom.xml")
assert drpFile.isFile() : "DRP should exist at " + drpFile

// Verify the original (non-shaded) artifact exists
File originalJar = new File(basedir, "target/mshade-419-1.0.jar")
assert originalJar.isFile() : "Original artifact should exist at " + originalJar

// Verify the shaded (attached) artifact exists with the classifier
File shadedJar = new File(basedir, "target/mshade-419-1.0-shaded.jar")
assert shadedJar.isFile() : "Shaded artifact should exist at " + shadedJar

// Verify that project.getFile() still points to the original pom.xml after shading,
// NOT to the dependency-reduced-pom.xml. This is critical because when
// shadedArtifactAttached=true, the original artifact is the main artifact and
// its POM comes from project.getFile(). If project.getFile() points to the DRP,
// the deployed POM will lose all compile dependencies.
File buildLog = new File(basedir, "../build.log")
if (!buildLog.isFile()) {
    buildLog = new File(basedir, "build.log")
}
assert buildLog.isFile() : "build.log should exist"

def lines = buildLog.readLines()
def projectFileLine = lines.find { it =~ /^\// && it.endsWith("/pom.xml") }
assert projectFileLine != null : "project.file value not found in build log (expected a path ending with /pom.xml)"
assert projectFileLine.endsWith("/pom.xml") : "project.file should be pom.xml, but was: " + projectFileLine
