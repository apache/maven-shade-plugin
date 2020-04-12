/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.maven.plugins.shade.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.junit.Test;

/**
 * Test for handling META-INF/service/...
 */
public class ServiceResourceTransformerTest {
    
    private List<Relocator> relocators = new ArrayList<Relocator>();

    @Test
    public void relocatedClasses() throws Exception {
        SimpleRelocator relocator =
            new SimpleRelocator( "org.foo", "borg.foo", null, Arrays.asList( "org.foo.exclude.*" ) );
        relocators.add( relocator );

        String content = "org.foo.Service\norg.foo.exclude.OtherService\n";
        byte[] contentBytes = content.getBytes( StandardCharsets.UTF_8 );
        InputStream contentStream = new ByteArrayInputStream( contentBytes );
        String contentResource = "META-INF/services/org.foo.something.another";
        String contentResourceShaded = "META-INF/services/borg.foo.something.another";

        ServicesResourceTransformer xformer = new ServicesResourceTransformer();
        xformer.processResource( contentResource, contentStream, relocators, 0 );
        contentStream.close();

        File tempJar = File.createTempFile("shade.", ".jar");
        tempJar.deleteOnExit();
        FileOutputStream fos = new FileOutputStream( tempJar );
        try ( JarOutputStream jos = new JarOutputStream( fos ) ) {
            xformer.modifyOutputStream( jos );
            jos.close();
            
            JarFile jarFile = new JarFile( tempJar );
            JarEntry jarEntry = jarFile.getJarEntry( contentResourceShaded );
            assertNotNull( jarEntry );
            try ( InputStream entryStream = jarFile.getInputStream( jarEntry ) ) {
                String xformedContent = IOUtils.toString( entryStream, "utf-8" );
                assertEquals( "borg.foo.Service" + System.getProperty( "line.separator" )
                    + "org.foo.exclude.OtherService" + System.getProperty( "line.separator" ), xformedContent );
            } finally {
                jarFile.close();
            }
        } finally {
            tempJar.delete();
        }
    }
    
    @Test
    public void concatanationAppliedMultipleTimes() throws Exception {
        SimpleRelocator relocator =
            new SimpleRelocator( "org.eclipse", "org.eclipse1234", null, null );
        relocators.add( relocator );
        
        String content = "org.eclipse.osgi.launch.EquinoxFactory\n";
        byte[] contentBytes = content.getBytes( "UTF-8" );
        InputStream contentStream = new ByteArrayInputStream( contentBytes );
        String contentResource = "META-INF/services/org.osgi.framework.launch.FrameworkFactory";

        ServicesResourceTransformer xformer = new ServicesResourceTransformer();
        xformer.processResource( contentResource, contentStream, relocators, 0 );
        contentStream.close();

        File tempJar = File.createTempFile("shade.", ".jar");
        tempJar.deleteOnExit();
        FileOutputStream fos = new FileOutputStream( tempJar );
        try ( JarOutputStream jos = new JarOutputStream( fos ) ) {
            xformer.modifyOutputStream( jos );
            jos.close();
            
            JarFile jarFile = new JarFile( tempJar );
            JarEntry jarEntry = jarFile.getJarEntry( contentResource );
            assertNotNull( jarEntry );
            try ( InputStream entryStream = jarFile.getInputStream( jarEntry ) ) {
                String xformedContent = IOUtils.toString(entryStream, "utf-8");
                assertEquals( "org.eclipse1234.osgi.launch.EquinoxFactory" + System.getProperty( "line.separator" ), xformedContent );
            } finally {
                jarFile.close();
            }
        } finally {
            tempJar.delete();
        }
    }

    @Test
    public void concatenation() throws Exception {
        SimpleRelocator relocator = new SimpleRelocator("org.foo", "borg.foo", null, null);
        relocators.add( relocator );
        
        String content = "org.foo.Service\n";
        byte[] contentBytes = content.getBytes( StandardCharsets.UTF_8 );
        InputStream contentStream = new ByteArrayInputStream( contentBytes );
        String contentResource = "META-INF/services/org.something.another";

        ServicesResourceTransformer xformer = new ServicesResourceTransformer();
        xformer.processResource( contentResource, contentStream, relocators, 0 );
        contentStream.close();

        content = "org.blah.Service\n";
        contentBytes = content.getBytes( StandardCharsets.UTF_8 );
        contentStream = new ByteArrayInputStream( contentBytes );
        contentResource = "META-INF/services/org.something.another";

        xformer.processResource( contentResource, contentStream, relocators, 0 );
        contentStream.close();

        File tempJar = File.createTempFile("shade.", ".jar");
        tempJar.deleteOnExit();
        FileOutputStream fos = new FileOutputStream( tempJar );
        try ( JarOutputStream jos = new JarOutputStream( fos ) ) {
            xformer.modifyOutputStream( jos );
            jos.close();

            JarFile jarFile = new JarFile( tempJar );
            JarEntry jarEntry = jarFile.getJarEntry( contentResource );
            assertNotNull( jarEntry );
            try ( InputStream entryStream = jarFile.getInputStream( jarEntry ) ) {
                String xformedContent = IOUtils.toString(entryStream, "utf-8");
                // must be two lines, with our two classes.
                String[] classes = xformedContent.split("\r?\n");
                boolean h1 = false;
                boolean h2 = false;
                for ( String name : classes )
                {
                    if ("org.blah.Service".equals( name ))
                    {
                        h1 = true;
                    }
                    else if ("borg.foo.Service".equals( name ))
                    {
                        h2 = true;
                    }
                }
                assertTrue( h1 && h2 );
            } finally {
                jarFile.close();
            }
        } finally {
            tempJar.delete();
        }
    }
}
