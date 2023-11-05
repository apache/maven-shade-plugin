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

def jarFile = new java.util.jar.JarFile( new File( basedir, "target/shade-1.0.jar" ) )
try
{
    // Although the original jar is NOT a Multi-Release, by shading in a Multi-Release jar this also must be a Multi-Release jar.
    def manifestJarEntry = jarFile.getJarEntry("META-INF/MANIFEST.MF")
    def manifestContent = jarFile.getInputStream(manifestJarEntry).getText()
    assert manifestContent.contains( 'Multi-Release: true' )

    File java8ShadedLog = new File( basedir, 'Java8-shaded.log' )
    File java8UnshadedLog = new File( basedir, 'Java8-unshaded.log' )
    File java11ShadedLog = new File( basedir, 'Java11-shaded.log' )
    File java11UnshadedLog = new File( basedir, 'Java11-unshaded.log' )
    File java17ShadedLog = new File( basedir, 'Java17-shaded.log' )
    File java17UnshadedLog = new File( basedir, 'Java17-unshaded.log' )

    assert java8ShadedLog.getText().equals(java8UnshadedLog.getText())
    assert java11ShadedLog.getText().equals(java11UnshadedLog.getText())
    assert java17ShadedLog.getText().equals(java17UnshadedLog.getText())

    // App class is unmodified
    assert null != jarFile.getJarEntry( "nl/example/Main.class" )

    // ALL original dependency classes must be gone
    assert null == jarFile.getJarEntry( "nl/basjes/maven/multijdk/Unused.class" )
    assert null == jarFile.getJarEntry( "nl/basjes/maven/multijdk/JavaVersion.class" )
    assert null == jarFile.getJarEntry( "nl/basjes/maven/multijdk/AbstractJavaVersion.class" )
    assert null == jarFile.getJarEntry( "nl/basjes/maven/multijdk/App.class" )
    assert null == jarFile.getJarEntry( "nl/basjes/maven/multijdk/Main.class" )

    assert null == jarFile.getJarEntry( "META-INF/versions/11/nl/basjes/maven/multijdk/Unused.class" )
    assert null == jarFile.getJarEntry( "META-INF/versions/11/nl/basjes/maven/multijdk/JavaVersion.class" )
    assert null == jarFile.getJarEntry( "META-INF/versions/11/nl/basjes/maven/multijdk/SpecificToJava11.class" )
    assert null == jarFile.getJarEntry( "META-INF/versions/11/nl/basjes/maven/multijdk/OnlyUsedInJava17.class" )
    assert null == jarFile.getJarEntry( "META-INF/versions/11/nl/basjes/maven/multijdk/App.class" )

    assert null == jarFile.getJarEntry( "META-INF/versions/17/nl/basjes/maven/multijdk/Unused.class" )
    assert null == jarFile.getJarEntry( "META-INF/versions/17/nl/basjes/maven/multijdk/SpecificToJava17.class" )
    assert null == jarFile.getJarEntry( "META-INF/versions/17/nl/basjes/maven/multijdk/App.class" )

    // The relocated must NOT be minimized
    assert null != jarFile.getJarEntry( "nl/example/shaded/multijdk/Unused.class" )
    assert null != jarFile.getJarEntry( "nl/example/shaded/multijdk/JavaVersion.class" )
    assert null != jarFile.getJarEntry( "nl/example/shaded/multijdk/AbstractJavaVersion.class" )
    assert null != jarFile.getJarEntry( "nl/example/shaded/multijdk/App.class" )
    assert null != jarFile.getJarEntry( "nl/example/shaded/multijdk/Main.class" )

    assert null != jarFile.getJarEntry( "META-INF/versions/11/nl/example/shaded/multijdk/Unused.class" )
    assert null != jarFile.getJarEntry( "META-INF/versions/11/nl/example/shaded/multijdk/JavaVersion.class" )
    assert null != jarFile.getJarEntry( "META-INF/versions/11/nl/example/shaded/multijdk/SpecificToJava11.class" )
    assert null != jarFile.getJarEntry( "META-INF/versions/11/nl/example/shaded/multijdk/OnlyUsedInJava17.class" )
    assert null != jarFile.getJarEntry( "META-INF/versions/11/nl/example/shaded/multijdk/App.class" )

    assert null != jarFile.getJarEntry( "META-INF/versions/17/nl/example/shaded/multijdk/Unused.class" )
    assert null != jarFile.getJarEntry( "META-INF/versions/17/nl/example/shaded/multijdk/SpecificToJava17.class" )
    assert null != jarFile.getJarEntry( "META-INF/versions/17/nl/example/shaded/multijdk/App.class" )
}
finally
{
    jarFile.close()
}
