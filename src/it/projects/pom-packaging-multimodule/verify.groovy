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

File shadedJar = new File( basedir, "target/shaded.jar" );
assert shadedJar.isFile()

// MSHADE-739: module-a declares no dependencies of its own - slf4j-api and junit are only ever
// inherited from the parent. Before the fix, shade wrote an explicit <dependency> for junit into
// module-a/dependency-reduced-pom.xml anyway, which is both redundant (the parent already provides
// it through inheritance, and the reduced POM keeps the same <parent> reference) and, since Maven
// 4.0.0-rc-3, rejected as a duplicate dependency. With nothing of its own to reduce, module-a now
// gets no dependency-reduced-pom.xml at all; if one is ever produced again, it must not declare any
// dependency.
File moduleAReducedPom = new File( basedir, "module-a/dependency-reduced-pom.xml" );
if ( moduleAReducedPom.isFile() )
{
    def ns = new groovy.xml.Namespace( "http://maven.apache.org/POM/4.0.0" )
    def pom = new XmlParser().parse( moduleAReducedPom )
    assert pom[ns.dependencies][ns.dependency].size() == 0
}
