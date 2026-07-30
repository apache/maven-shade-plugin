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

import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.util.jar.JarFile

def shaded = new File( basedir, "app/target/app-1.0.jar" )
assert shaded.isFile()

def reference = ModuleFinder.of( shaded.toPath() ).find( "app.module" ).orElseThrow {
    new AssertionError( "The shaded JAR is not the app.module module" )
}
def descriptor = reference.descriptor()

assert descriptor.name() == "app.module"
assert descriptor.requires()*.name().contains( "external.module" )
assert descriptor.requires()*.name().contains( "java.sql" )
assert !descriptor.requires()*.name().contains( "automatic.module" )
assert !descriptor.requires()*.name().contains( "embedded.module" )
assert descriptor.requires().find { it.name() == "external.module" }
        .modifiers().contains( ModuleDescriptor.Requires.Modifier.TRANSITIVE )
def optional = descriptor.requires().find { it.name() == "optional.module" }
assert optional != null
assert optional.modifiers().contains( ModuleDescriptor.Requires.Modifier.STATIC )
assert optional.modifiers().contains( ModuleDescriptor.Requires.Modifier.TRANSITIVE )
assert ( descriptor.exports()*.source() as Set ) ==
        [ "app.api", "shaded.automatic", "shaded.internal", "shaded.library" ] as Set
assert ( descriptor.opens()*.source() as Set ) ==
        [ "shaded.automatic", "shaded.internal" ] as Set
assert descriptor.packages() ==
        [ "app.api", "shaded.automatic", "shaded.internal", "shaded.library" ] as Set
assert descriptor.uses() ==
        [ "external.api.AutomaticService", "external.api.Service" ] as Set

def service = descriptor.provides().find { it.service() == "external.api.Service" }
assert service != null
assert service.providers() == [ "shaded.internal.Provider" ]
def automaticService =
        descriptor.provides().find { it.service() == "external.api.AutomaticService" }
assert automaticService != null
assert automaticService.providers() == [ "shaded.automatic.AutomaticProvider" ]

def external = new File( basedir, "external/target/external-1.0.jar" )
assert external.isFile()

def finder = ModuleFinder.of( shaded.toPath(), external.toPath() )
def configuration = ModuleLayer.boot().configuration()
        .resolve( finder, ModuleFinder.of(), [ "app.module" ] as Set )
def layer = ModuleLayer.boot().defineModulesWithOneLoader(
        configuration, ClassLoader.getSystemClassLoader() )
def library = layer.findLoader( "app.module" ).loadClass( "shaded.library.Library" )
assert library.module.name == "app.module"
def automaticLibrary =
        Class.forName( "shaded.automatic.AutomaticLibrary", true, layer.findLoader( "app.module" ) )
assert automaticLibrary.module.name == "app.module"
def application = layer.findLoader( "app.module" ).loadClass( "app.api.Application" )
def thread = Thread.currentThread()
def contextLoader = thread.contextClassLoader
try
{
    thread.contextClassLoader = layer.findLoader( "app.module" )
    assert application.getMethod( "loadAutomaticService" ).invoke( null ) == "automatic"
}
finally
{
    thread.contextClassLoader = contextLoader
}

def jar = new JarFile( shaded )
try
{
    assert jar.getJarEntry( "module-info.class" ) == null
    assert jar.getJarEntry( "META-INF/versions/11/module-info.class" ) != null
    assert jar.getJarEntry( "META-INF/services/external.api.AutomaticService" ) != null
    assert jar.getJarEntry( "shaded/automatic/AutomaticLibrary.class" ) != null
    assert jar.getJarEntry( "shaded/automatic/AutomaticProvider.class" ) != null
    assert jar.getJarEntry( "shaded/library/Library.class" ) != null
    assert jar.getJarEntry( "shaded/internal/Provider.class" ) != null
    assert jar.getJarEntry( "embedded/api/Library.class" ) == null
    assert jar.getJarEntry( "embedded/internal/Provider.class" ) == null
    assert jar.getJarEntry( "automatic/library/AutomaticLibrary.class" ) == null
    assert jar.manifest.mainAttributes.getValue( "Automatic-Module-Name" ) == "app.module"
    assert jar.manifest.mainAttributes.getValue( "Multi-Release" ) == "true"
}
finally
{
    jar.close()
}
