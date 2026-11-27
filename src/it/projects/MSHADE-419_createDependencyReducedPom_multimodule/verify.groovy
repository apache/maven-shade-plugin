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

import java.util.jar.*
import org.codehaus.plexus.util.*

// note that the following checks were passing even before any fix for MSHADE-419,
// since the generated poms and jars are generated correctly.
// only compilation of the impl-user module is failing because it uses the normal jar but
// without its transitive dependencies

String jarPrefix = "impl/target/mshade-419-impl-1.0"
String jarPomPath = "META-INF/maven/org.apache.maven.its.shade.cdrp-mm/mshade-419-impl/pom.xml"
String apiDependencyString = "<artifactId>mshade-419-api</artifactId>"

String mainPomFileContent = FileUtils.fileRead( new File( basedir, "impl/pom.xml" ), "UTF-8" )
if ( !mainPomFileContent.contains( apiDependencyString ) )
{
    throw new IllegalStateException( "The pom.xml should still contain the api dependency:\n" + mainPomFileContent )
}

String reducedPomFileContent = FileUtils.fileRead( new File( basedir, "impl/dependency-reduced-pom.xml" ), "UTF-8" )
if ( reducedPomFileContent.contains( apiDependencyString ) )
{
    throw new IllegalStateException( "The dependency-reduced-pom.xml should not contain the api dependency:\n" + reducedPomFileContent )
}

JarFile jarFile = null
try
{
    jarFile = new JarFile ( new File( basedir, jarPrefix + ".jar" ) )
    JarEntry jarEntry = jarFile.getEntry(jarPomPath  )
    String pomFile = IOUtil.toString( jarFile.getInputStream( jarEntry ), "UTF-8" )
    if ( !pomFile.contains( apiDependencyString ) )
    {
        throw new IllegalStateException( "The pom.xml in the normal jar should still contain the api dependency:\n" + pomFile )
    }
}
finally
{
    if ( jarFile != null )
    {
        jarFile.close()
    }
}
