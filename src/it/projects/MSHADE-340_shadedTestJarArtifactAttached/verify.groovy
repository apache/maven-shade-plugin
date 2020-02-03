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

def originalUberJar = new File( basedir, "uber/target/mshade-340-uber-1.0.jar" )
def jackOfAllUberJar = new File( basedir, "uber/target/mshade-340-uber-1.0-jack-of-all.jar" )
def originalUberTestJar = new File ( basedir, "uber/target/mshade-340-uber-1.0-tests.jar" )
def jackOfAllUberTestJar = new File ( basedir, "uber/target/mshade-340-uber-1.0-jack-of-all-tests.jar" )
def originalUberSourcesJar = new File( basedir, "uber/target/mshade-340-uber-1.0-sources.jar" )
def jackOfAllUberSourcesJar = new File( basedir, "uber/target/mshade-340-uber-1.0-jack-of-all-sources.jar" )
def originalUberTestSourcesJar = new File ( basedir, "uber/target/mshade-340-uber-1.0-test-sources.jar" )
def jackOfAllUberTestSourcesJar = new File ( basedir, "uber/target/mshade-340-uber-1.0-jack-of-all-test-sources.jar" )

assert originalUberJar.exists()
assert jackOfAllUberJar.exists()
assert originalUberTestJar.exists()
assert jackOfAllUberTestJar.exists()
assert originalUberSourcesJar.exists()
assert jackOfAllUberSourcesJar.exists()
assert originalUberTestSourcesJar.exists()
assert jackOfAllUberTestSourcesJar.exists()

def originalUberJarFile = new java.util.jar.JarFile( originalUberJar )
try
{
    assert null == originalUberJarFile.getJarEntry( "Api.class" )
    assert null == originalUberJarFile.getJarEntry( "Impl.class" )
    assert null != originalUberJarFile.getJarEntry( "Uber.class" )
}
finally
{
    originalUberJarFile.close()
}

def jackOfAllUberJarFile = new java.util.jar.JarFile( jackOfAllUberJar )
try
{
    assert null != jackOfAllUberJarFile.getJarEntry( "Api.class" )
    assert null != jackOfAllUberJarFile.getJarEntry( "Impl.class" )
    assert null != jackOfAllUberJarFile.getJarEntry( "Uber.class" )
}
finally
{
    jackOfAllUberJarFile.close()
}

def originalUberTestJarFile = new java.util.jar.JarFile( originalUberTestJar )
try
{
    assert null == originalUberTestJarFile.getJarEntry( "ApiTest.class" )
    assert null == originalUberTestJarFile.getJarEntry( "ImplTest.class" )
    assert null != originalUberTestJarFile.getJarEntry( "UberTest.class" )
}
finally
{
    originalUberTestJarFile.close()
}

def jackOfAllUberTestJarFile = new java.util.jar.JarFile( jackOfAllUberTestJar )
try
{
    assert null != jackOfAllUberTestJarFile.getJarEntry( "ApiTest.class" )
    assert null != jackOfAllUberTestJarFile.getJarEntry( "ImplTest.class" )
    assert null != jackOfAllUberTestJarFile.getJarEntry( "UberTest.class" )
}
finally
{
    jackOfAllUberTestJarFile.close()
}

def originalUberSourcesJarFile = new java.util.jar.JarFile( originalUberSourcesJar )
try
{
    assert null == originalUberSourcesJarFile.getJarEntry( "Api.java" )
    assert null == originalUberSourcesJarFile.getJarEntry( "Impl.java" )
    assert null != originalUberSourcesJarFile.getJarEntry( "Uber.java" )
}
finally
{
    originalUberSourcesJarFile.close()
}

def jackOfAllUberSourcesJarFile = new java.util.jar.JarFile( jackOfAllUberSourcesJar )
try
{
    assert null != jackOfAllUberSourcesJarFile.getJarEntry( "Api.java" )
    assert null != jackOfAllUberSourcesJarFile.getJarEntry( "Impl.java" )
    assert null != jackOfAllUberSourcesJarFile.getJarEntry( "Uber.java" )
}
finally
{
    jackOfAllUberSourcesJarFile.close()
}

def originalUberTestSourcesJarFile = new java.util.jar.JarFile( originalUberTestSourcesJar )
try
{
    assert null == originalUberTestSourcesJarFile.getJarEntry( "ApiTest.java" )
    assert null == originalUberTestSourcesJarFile.getJarEntry( "ImplTest.java" )
    assert null != originalUberTestSourcesJarFile.getJarEntry( "UberTest.java" )
}
finally
{
    originalUberTestSourcesJarFile.close()
}

def jackOfAllUberTestSourcesJarFile = new java.util.jar.JarFile( jackOfAllUberTestSourcesJar )
try
{
    assert null != jackOfAllUberTestSourcesJarFile.getJarEntry( "ApiTest.java" )
    assert null != jackOfAllUberTestSourcesJarFile.getJarEntry( "ImplTest.java" )
    assert null != jackOfAllUberTestSourcesJarFile.getJarEntry( "UberTest.java" )
}
finally
{
    jackOfAllUberTestSourcesJarFile.close()
}
