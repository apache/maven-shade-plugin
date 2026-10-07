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

import groovy.xml.XmlParser

// Parse without namespace awareness, so that the checks work no matter whether
// (or with which namespace) the POM was written. A namespace mismatch must never
// let the dependency checks pass vacuously.
def parsePom = { File f -> new XmlParser(false, false).parse(f) }
def dependenciesOf = { pom ->
    pom.dependencies.dependency.collect { "${it.groupId.text()}:${it.artifactId.text()}" }
}

File drpFile = new File(basedir, 'dependency-reduced-pom.xml')

def drp = parsePom(drpFile)
assert dependenciesOf(drp).isEmpty() : "dependency-reduced POM still declares dependencies: ${dependenciesOf(drp)}"

def originalPom = parsePom(new File(basedir, 'pom.xml'))
assert dependenciesOf(originalPom).size() == 4 : 'The original project POM must remain unchanged'

File installedPom = new File(localRepositoryPath, 'org/apache/maven/its/shade/drp/864/test/1.0/test-1.0.pom')
assert installedPom.isFile() : "POM was not installed: $installedPom"

def pom = parsePom(installedPom)
assert pom.artifactId.text() == 'test' : "Unexpected content in $installedPom"
assert dependenciesOf(pom).isEmpty() : "Installed POM still declares shaded dependencies ${dependenciesOf(pom)}; " +
        "consumers would resolve them (and their transitive dependencies) again. Installed POM: $installedPom"

File buildPom = new File(localRepositoryPath, 'org/apache/maven/its/shade/drp/864/test/1.0/test-1.0-build.pom')
if (buildPom.exists()) {
    def build = parsePom(buildPom)
    assert dependenciesOf(build).isEmpty() : "Build POM still declares shaded dependencies ${dependenciesOf(build)}"
}

return true
