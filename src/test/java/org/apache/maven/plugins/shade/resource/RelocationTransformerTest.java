package org.apache.maven.plugins.shade.resource;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.codehaus.plexus.util.IOUtil;
import org.junit.Test;

/**
 * Ensure the relation transformer uses relocators.
 */
public class RelocationTransformerTest
{
    @Test
    public void relocate() throws IOException
    {
        AppendingTransformer delegate = new AppendingTransformer();
        delegate.resource = "foo/bar.txt";

        RelocationTransformer resourceTransformer = new RelocationTransformer();
        resourceTransformer.setDelegates( Collections.<ResourceTransformer>singletonList( delegate ) );

        assertTrue( resourceTransformer.canTransformResource( "foo/bar.txt" ) );
        resourceTransformer.processResource(
                "foo/bar.txt",
                new ByteArrayInputStream("a=javax.foo.bar".getBytes( StandardCharsets.UTF_8 )),
                Collections.<Relocator>singletonList( new SimpleRelocator(
                        "javax", "jakarta", null, null ) ));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try ( JarOutputStream jarOutputStream = new JarOutputStream(out) )
        {
            resourceTransformer.modifyOutputStream(jarOutputStream);
        }
        try ( JarInputStream jarInputStream = new JarInputStream( new ByteArrayInputStream( out.toByteArray() ) ))
        {
            final JarEntry entry = jarInputStream.getNextJarEntry();
            assertNotNull( entry );
            assertEquals( "foo/bar.txt", entry.getName() );
            assertEquals( "a=jakarta.foo.bar", IOUtil.toString( jarInputStream ).trim() );
            assertNull( jarInputStream.getNextJarEntry() );
        }
    }
}
