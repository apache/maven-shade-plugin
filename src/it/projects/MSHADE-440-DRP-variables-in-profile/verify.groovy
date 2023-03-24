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


// The problem situation that is recreated is that in some places in a DRP
// properties are being expanded to their full values in an active profile.
// The exact same code fragment outside of a profile is left untouched.

File pomFile = new File( basedir, "dependency-reduced-pom.xml" )
assert pomFile.isFile()

content = pomFile.text

assert content.contains( '<id>CHECK Outside profile ${project.basedir}.</id>' )
assert content.contains( '<id>CHECK Inside inactive profile ${project.basedir}.</id>' )
assert content.contains( '<id>CHECK Inside active profile ${project.basedir}.</id>' )

assert content.contains( '<testOutputDirectory>CHECK Outside profile ${project.basedir}.</testOutputDirectory>' )
assert content.contains( '<testOutputDirectory>CHECK Inside inactive profile ${project.basedir}.</testOutputDirectory>' )
assert content.contains( '<testOutputDirectory>CHECK Inside active profile ${project.basedir}.</testOutputDirectory>' )

assert content.contains( '<prop>CHECK Outside profile ${project.basedir}.</prop>' )
assert content.contains( '<prop>CHECK Inside inactive profile ${project.basedir}.</prop>' )
assert content.contains( '<prop>CHECK Inside active profile ${project.basedir}.</prop>' )
