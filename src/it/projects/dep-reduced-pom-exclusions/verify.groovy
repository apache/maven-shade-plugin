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

File pomFile = new File( basedir, "dependency-reduced-pom.xml" )
assert pomFile.isFile()

def ns = new groovy.xml.Namespace("http://maven.apache.org/POM/4.0.0") 
def pom = new XmlParser().parse( pomFile )

assert pom[ns.modelVersion].size() == 1
assert pom[ns.dependencies][ns.dependency].size() == 2
assert pom[ns.dependencies][ns.dependency][0][ns.exclusions][ns.exclusion].size() == 1
assert pom[ns.dependencies][ns.dependency][1][ns.exclusions][ns.exclusion].size() == 1
