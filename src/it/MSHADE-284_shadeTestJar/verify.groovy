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

def jarFile = new java.util.jar.JarFile( new File( basedir, "uber/target/mshade-284-uber-1.0.jar" ) )
try
{
    assert null != jarFile.getJarEntry( "Api.class" )
    assert null != jarFile.getJarEntry( "api-resource.txt" )
    assert null != jarFile.getJarEntry( "Impl.class" )
    assert null != jarFile.getJarEntry( "impl-resource.txt" )
    assert null == jarFile.getJarEntry( "ApiTest.class" )
    assert null == jarFile.getJarEntry( "api-test-resource.txt" )
    assert null == jarFile.getJarEntry( "ImplTest.class" )
    assert null == jarFile.getJarEntry( "impl-test-resource.txt" )
}
finally
{
    jarFile.close()
}

def testJarFile = new java.util.jar.JarFile( new File( basedir, "uber/target/mshade-284-uber-1.0-tests.jar" ) )
try
{
    assert null == testJarFile.getJarEntry( "Api.class" )
    assert null == testJarFile.getJarEntry( "api-resource.txt" )
    assert null == testJarFile.getJarEntry( "Impl.class" )
    assert null == testJarFile.getJarEntry( "impl-resource.txt" )
    assert null != testJarFile.getJarEntry( "ApiTest.class" )
    assert null != testJarFile.getJarEntry( "api-test-resource.txt" )
    assert null != testJarFile.getJarEntry( "ImplTest.class" )
    assert null != testJarFile.getJarEntry( "impl-test-resource.txt" )
}
finally
{
    testJarFile.close()
}
