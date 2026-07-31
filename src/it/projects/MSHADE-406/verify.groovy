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

def shadedJar = new File( basedir, "app/target/app-1.0.jar" )
assert shadedJar.isFile()

def jar = new java.util.jar.JarFile( shadedJar )
try
{
    assert jar.manifest.mainAttributes.getValue( "Multi-Release" ) == "true"
    assert jar.getJarEntry( "reproducer/shaded/mr/Versioned.class" ) != null
    assert jar.getJarEntry(
            "META-INF/versions/11/reproducer/shaded/mr/Versioned.class" ) != null

    assert jar.getJarEntry( "reproducer/mr/Versioned.class" ) == null
    assert jar.getJarEntry( "META-INF/versions/11/reproducer/mr/Versioned.class" ) == null

    assert jar.getJarEntry( "reproducer/shaded/mr/Unused.class" ) == null
    assert jar.getJarEntry(
            "META-INF/versions/11/reproducer/shaded/mr/Unused.class" ) == null
    assert jar.getJarEntry( "META-INF/versions/11/module-info.class" ) == null
}
finally
{
    jar.close()
}

def java = new File( System.getProperty( "java.home" ), "bin/java" )
def process = new ProcessBuilder( java.absolutePath, "-jar", shadedJar.absolutePath )
        .redirectErrorStream( true )
        .start()
def output = process.inputStream.getText( "UTF-8" )
assert process.waitFor() == 0 : output
assert output.trim() == "java11"
