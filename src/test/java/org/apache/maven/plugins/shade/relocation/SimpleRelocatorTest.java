package org.apache.maven.plugins.shade.relocation;

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


import junit.framework.TestCase;

import java.util.Arrays;
import java.util.Collections;

/**
 * Test for {@link SimpleRelocator}.
 *
 * @author Benjamin Bentmann
 *
 */
public class SimpleRelocatorTest
    extends TestCase
{

    public void testCanRelocatePath()
    {
        SimpleRelocator relocator;

        relocator = new SimpleRelocator( "org.foo", null, null, null );
        assertTrue( relocator.canRelocatePath( "org/foo/Class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/Class.class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/bar/Class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/bar/Class.class" ) );
        assertFalse( relocator.canRelocatePath( "com/foo/bar/Class" ) );
        assertFalse( relocator.canRelocatePath( "com/foo/bar/Class.class" ) );
        assertFalse( relocator.canRelocatePath( "org/Foo/Class" ) );
        assertFalse( relocator.canRelocatePath( "org/Foo/Class.class" ) );

        relocator = new SimpleRelocator( "org.foo", null, null, Arrays.asList(
                "org.foo.Excluded", "org.foo.public.*", "org.foo.Public*Stuff") );
        assertTrue( relocator.canRelocatePath( "org/foo/Class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/Class.class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/excluded" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/Excluded" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/Excluded.class" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/public" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/public/Class" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/public/Class.class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/publicRELOC/Class" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/PrivateStuff" ) );
        assertTrue( relocator.canRelocatePath( "org/foo/PrivateStuff.class" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/PublicStuff" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/PublicStuff.class" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/PublicUtilStuff" ) );
        assertFalse( relocator.canRelocatePath( "org/foo/PublicUtilStuff.class" ) );
    }

    public void testCanRelocateClass()
    {
        SimpleRelocator relocator;

        relocator = new SimpleRelocator( "org.foo", null, null, null );
        assertTrue( relocator.canRelocateClass( "org.foo.Class" ) );
        assertTrue( relocator.canRelocateClass( "org.foo.bar.Class" ) );
        assertFalse( relocator.canRelocateClass( "com.foo.bar.Class" ) );
        assertFalse( relocator.canRelocateClass( "org.Foo.Class" ) );

        relocator = new SimpleRelocator( "org.foo", null, null, Arrays.asList(
                "org.foo.Excluded", "org.foo.public.*", "org.foo.Public*Stuff") );
        assertTrue( relocator.canRelocateClass( "org.foo.Class" ) );
        assertTrue( relocator.canRelocateClass( "org.foo.excluded" ) );
        assertFalse( relocator.canRelocateClass( "org.foo.Excluded" ) );
        assertFalse( relocator.canRelocateClass( "org.foo.public" ) );
        assertFalse( relocator.canRelocateClass( "org.foo.public.Class" ) );
        assertTrue( relocator.canRelocateClass( "org.foo.publicRELOC.Class" ) );
        assertTrue( relocator.canRelocateClass( "org.foo.PrivateStuff" ) );
        assertFalse( relocator.canRelocateClass( "org.foo.PublicStuff" ) );
        assertFalse( relocator.canRelocateClass( "org.foo.PublicUtilStuff" ) );
    }

    public void testCanRelocateRawString()
    {
        SimpleRelocator relocator;

        relocator = new SimpleRelocator( "org/foo", null, null, null, true );
        assertTrue( relocator.canRelocatePath( "(I)org/foo/bar/Class;" ) );
        
        relocator = new SimpleRelocator( "^META-INF/org.foo.xml$", null, null, null, true );
        assertTrue( relocator.canRelocatePath( "META-INF/org.foo.xml" ) );
    }

    //MSHADE-119, make sure that the easy part of this works.
    public void testCanRelocateAbsClassPath() 
    {
        SimpleRelocator relocator = new SimpleRelocator( "org.apache.velocity", "org.apache.momentum", null, null );
        assertEquals("/org/apache/momentum/mass.properties", relocator.relocatePath( "/org/apache/velocity/mass.properties" ) );
    }

    public void testCanRelocateAbsClassPathWithExcludes()
    {
        SimpleRelocator relocator = new SimpleRelocator( "org/apache/velocity", "org/apache/momentum", null,
                                                         Arrays.asList( "org/apache/velocity/excluded/*" ) );
        assertTrue( relocator.canRelocatePath( "/org/apache/velocity/mass.properties" ) );
        assertTrue( relocator.canRelocatePath( "org/apache/velocity/mass.properties" ) );
        assertFalse( relocator.canRelocatePath( "/org/apache/velocity/excluded/mass.properties" ) );
        assertFalse( relocator.canRelocatePath( "org/apache/velocity/excluded/mass.properties" ) );
    }

    public void testCanRelocateAbsClassPathWithIncludes()
    {
        SimpleRelocator relocator = new SimpleRelocator( "org/apache/velocity", "org/apache/momentum",
                                                         Arrays.asList( "org/apache/velocity/included/*" ), null );
        assertFalse( relocator.canRelocatePath( "/org/apache/velocity/mass.properties" ) );
        assertFalse( relocator.canRelocatePath( "org/apache/velocity/mass.properties" ) );
        assertTrue( relocator.canRelocatePath( "/org/apache/velocity/included/mass.properties" ) );
        assertTrue( relocator.canRelocatePath( "org/apache/velocity/included/mass.properties" ) );
    }

    public void testRelocatePath()
    {
        SimpleRelocator relocator;

        relocator = new SimpleRelocator( "org.foo", null, null, null );
        assertEquals( "hidden/org/foo/bar/Class.class", relocator.relocatePath( "org/foo/bar/Class.class" ) );

        relocator = new SimpleRelocator( "org.foo", "private.stuff", null, null );
        assertEquals( "private/stuff/bar/Class.class", relocator.relocatePath( "org/foo/bar/Class.class" ) );
    }

    public void testRelocateClass()
    {
        SimpleRelocator relocator;

        relocator = new SimpleRelocator( "org.foo", null, null, null );
        assertEquals( "hidden.org.foo.bar.Class", relocator.relocateClass( "org.foo.bar.Class" ) );

        relocator = new SimpleRelocator( "org.foo", "private.stuff", null, null );
        assertEquals( "private.stuff.bar.Class", relocator.relocateClass( "org.foo.bar.Class" ) );
    }

    public void testRelocateRawString()
    {
        SimpleRelocator relocator;

        relocator = new SimpleRelocator( "Lorg/foo", "Lhidden/org/foo", null, null, true );
        assertEquals( "(I)Lhidden/org/foo/bar/Class;", relocator.relocatePath( "(I)Lorg/foo/bar/Class;" ) );

        relocator = new SimpleRelocator( "^META-INF/org.foo.xml$", "META-INF/hidden.org.foo.xml", null, null, true );
        assertEquals( "META-INF/hidden.org.foo.xml", relocator.relocatePath( "META-INF/org.foo.xml" ) );
    }
    
    public void testRelocateMavenFiles()
    {
        SimpleRelocator relocator =
            new SimpleRelocator( "META-INF/maven", "META-INF/shade/maven", null,
                                 Collections.singletonList( "META-INF/maven/com.foo.bar/artifactId/pom.*" ) );
        assertFalse( relocator.canRelocatePath( "META-INF/maven/com.foo.bar/artifactId/pom.properties" ) );
        assertFalse( relocator.canRelocatePath( "META-INF/maven/com.foo.bar/artifactId/pom.xml" ) );
        assertTrue( relocator.canRelocatePath( "META-INF/maven/com/foo/bar/artifactId/pom.properties" ) );
        assertTrue( relocator.canRelocatePath( "META-INF/maven/com/foo/bar/artifactId/pom.xml" ) );
        assertTrue( relocator.canRelocatePath( "META-INF/maven/com-foo-bar/artifactId/pom.properties" ) );
        assertTrue( relocator.canRelocatePath( "META-INF/maven/com-foo-bar/artifactId/pom.xml" ) );

    }
}
