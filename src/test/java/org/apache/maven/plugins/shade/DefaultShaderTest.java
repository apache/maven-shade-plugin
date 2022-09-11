package org.apache.maven.plugins.shade;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.AppendingTransformer;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.Os;
import org.junit.Assert;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class DefaultShaderTest
{
    private static final String[] EXCLUDES = new String[] { "org/codehaus/plexus/util/xml/Xpp3Dom",
        "org/codehaus/plexus/util/xml/pull.*" };

    @Test
    public void testNoopWhenNotRelocated() throws IOException, MojoExecutionException {
        final File plexusJar = new File("src/test/jars/plexus-utils-1.4.1.jar" );
        final File shadedOutput = new File( "target/foo-custom_testNoopWhenNotRelocated.jar" );

        final Set<File> jars = new LinkedHashSet<>();
        jars.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );
        jars.add( plexusJar );

        final Relocator relocator = new SimpleRelocator(
            "org/codehaus/plexus/util/cli", "relocated/plexus/util/cli",
            Collections.<String>emptyList(), Collections.<String>emptyList() );

        final ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( jars );
        shadeRequest.setRelocators( Collections.singletonList( relocator ) );
        shadeRequest.setResourceTransformers( Collections.<ResourceTransformer>emptyList() );
        shadeRequest.setFilters( Collections.<Filter>emptyList() );
        shadeRequest.setUberJar( shadedOutput );

        final DefaultShader shader = newShader();
        shader.shade( shadeRequest );

        try ( final JarFile originalJar = new JarFile( plexusJar );
              final JarFile shadedJar = new JarFile( shadedOutput ) )
        {
            // ASM processes all class files. In doing so, it modifies them, even when not relocating anything.
            // Before MSHADE-391, the processed files were written to the uber JAR, which did no harm, but made it
            // difficult to find out by simple file comparison, if a file was actually relocated or not. Now, Shade
            // makes sure to always write the original file if the class neither was relocated itself nor references
            // other, relocated classes. So we are checking for regressions here. 
            assertTrue( areEqual( originalJar, shadedJar,
                "org/codehaus/plexus/util/Expand.class" ) );

            // Relocated files should always be different, because they contain different package names in their byte
            // code. We should verify this anyway, in order to avoid an existing class file from simply being moved to
            // another location without actually having been relocated internally.
            assertFalse( areEqual(
                originalJar, shadedJar,
                "org/codehaus/plexus/util/cli/Arg.class", "relocated/plexus/util/cli/Arg.class" ) );
        }
        int result = 0;
        for ( final String msg : debugMessages.getAllValues() )
        {
            if ( "Rewrote class bytecode: org/codehaus/plexus/util/cli/Arg.class".equals(msg) )
            {
                result |= 1;
            }
            else if ( "Keeping original class bytecode: org/codehaus/plexus/util/Expand.class".equals(msg) )
            {
                result |= 2;
            }
        }
        assertEquals( 3 /* 1 | 2 */ , result);
    }

    @Test
    public void testOverlappingResourcesAreLogged() throws IOException, MojoExecutionException {
        final DefaultShader shader = newShader();

        // we will shade two jars and expect to see META-INF/MANIFEST.MF overlaps, this will always be true
        // but this can lead to a broken deployment if intended for OSGi or so, so even this should be logged
        final Set<File> set = new LinkedHashSet<>();
        set.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );
        set.add( new File( "src/test/jars/plexus-utils-1.4.1.jar" ) );

        final ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setRelocators( Collections.<Relocator>emptyList() );
        shadeRequest.setResourceTransformers( Collections.<ResourceTransformer>emptyList() );
        shadeRequest.setFilters( Collections.<Filter>emptyList() );
        shadeRequest.setUberJar( new File( "target/foo-custom_testOverlappingResourcesAreLogged.jar" ) );
        shader.shade( shadeRequest );

        assertThat(warnMessages.getAllValues(),
            hasItem(containsString("plexus-utils-1.4.1.jar, test-project-1.0-SNAPSHOT.jar define 1 overlapping resource:")));
        assertThat(warnMessages.getAllValues(),
            hasItem(containsString("- META-INF/MANIFEST.MF")));
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            assertThat(debugMessages.getAllValues(),
                hasItem(containsString("We have a duplicate META-INF/MANIFEST.MF in src\\test\\jars\\plexus-utils-1.4.1.jar")));
        }
        else {
            assertThat(debugMessages.getAllValues(),
                hasItem(containsString("We have a duplicate META-INF/MANIFEST.MF in src/test/jars/plexus-utils-1.4.1.jar")));
        }
    }

    @Test
    public void testOverlappingResourcesAreLoggedExceptATransformerHandlesIt() throws Exception {
        TemporaryFolder temporaryFolder = new TemporaryFolder();
        try {
            Set<File> set = new LinkedHashSet<>();
            temporaryFolder.create();
            File j1 = temporaryFolder.newFile("j1.jar");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(j1))) {
                jos.putNextEntry(new JarEntry("foo.txt"));
                jos.write("c1".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            File j2 = temporaryFolder.newFile("j2.jar");
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(j2))) {
                jos.putNextEntry(new JarEntry("foo.txt"));
                jos.write("c2".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            set.add(j1);
            set.add(j2);

            AppendingTransformer transformer = new AppendingTransformer();
            Field resource = AppendingTransformer.class.getDeclaredField("resource");
            resource.setAccessible(true);
            resource.set(transformer, "foo.txt");

            ShadeRequest shadeRequest = new ShadeRequest();
            shadeRequest.setJars(set);
            shadeRequest.setRelocators(Collections.<Relocator>emptyList());
            shadeRequest.setResourceTransformers(Collections.<ResourceTransformer>singletonList(transformer));
            shadeRequest.setFilters(Collections.<Filter>emptyList());
            shadeRequest.setUberJar(new File("target/foo-custom_testOverlappingResourcesAreLogged.jar"));

            DefaultShader shaderWithTransformer = newShader();
            shaderWithTransformer.shade(shadeRequest);

            assertThat(warnMessages.getAllValues().size(), is(0) );

            DefaultShader shaderWithoutTransformer = newShader();
            shadeRequest.setResourceTransformers(Collections.<ResourceTransformer>emptyList());
            shaderWithoutTransformer.shade(shadeRequest);

            assertThat(warnMessages.getAllValues(),
                hasItems(containsString("j1.jar, j2.jar define 1 overlapping resource:")));
            assertThat(warnMessages.getAllValues(),
                hasItems(containsString("- foo.txt")));
        }
        finally {
            temporaryFolder.delete();
        }
    }

    @Test
    public void testShaderWithDefaultShadedPattern()
        throws Exception
    {
        shaderWithPattern( null, new File( "target/foo-default.jar" ), EXCLUDES );
    }

    @Test
    public void testShaderWithStaticInitializedClass()
        throws Exception
    {
        Shader s = newShader();

        Set<File> set = new LinkedHashSet<>();

        set.add( new File( "src/test/jars/test-artifact-1.0-SNAPSHOT.jar" ) );

        List<Relocator> relocators = new ArrayList<>();

        relocators.add( new SimpleRelocator( "org.apache.maven.plugins.shade", null, Collections.emptyList(), Collections.emptyList() ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        List<Filter> filters = new ArrayList<>();

        File file = new File( "target/testShaderWithStaticInitializedClass.jar" );

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( file );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );

        s.shade( shadeRequest );

        try ( URLClassLoader cl = new URLClassLoader( new URL[] { file.toURI().toURL() } ) ) {
          Class<?> c = cl.loadClass( "hidden.org.apache.maven.plugins.shade.Lib" );
          Object o = c.newInstance();
          assertEquals( "foo.bar/baz", c.getDeclaredField( "CONSTANT" ).get( o ) );
        }
    }

    @Test
    public void testShaderWithCustomShadedPattern()
        throws Exception
    {
        shaderWithPattern( "org/shaded/plexus/util", new File( "target/foo-custom.jar" ), EXCLUDES );
    }

    @Test
    public void testShaderWithoutExcludesShouldRemoveReferencesOfOriginalPattern()
        throws Exception
    {
        // FIXME: shaded jar should not include references to org/codehaus/* (empty dirs) or org.codehaus.* META-INF
        // files.
        shaderWithPattern( "org/shaded/plexus/util", new File( "target/foo-custom-without-excludes.jar" ),
                           new String[] {} );
    }

    @Test
    public void testShaderWithRelocatedClassname()
        throws Exception
    {
        DefaultShader s = newShader();

        Set<File> set = new LinkedHashSet<>();

        set.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util/", "_plexus/util/__", Collections.emptyList(),
                Collections.emptyList() ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add( new ComponentsXmlResourceTransformer() );

        List<Filter> filters = new ArrayList<>();

        File file = new File( "target/foo-relocate-class.jar" );

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( file );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );

        s.shade( shadeRequest );

        try ( URLClassLoader cl = new URLClassLoader( new URL[] { file.toURI().toURL() } ) ) {
          Class<?> c = cl.loadClass( "_plexus.util.__StringUtils" );
          // first, ensure it works:
          Object o = c.newInstance();
          assertEquals( "", c.getMethod( "clean", String.class ).invoke( o, (String) null ) );

          // now, check that its source file was rewritten:
          final String[] source = { null };
          final ClassReader classReader = new ClassReader( cl.getResourceAsStream( "_plexus/util/__StringUtils.class" ) );
          classReader.accept( new ClassVisitor( Opcodes.ASM4 )
          {
            @Override
            public void visitSource( String arg0, String arg1 )
            {
                super.visitSource( arg0, arg1 );
                source[0] = arg0;
            }
          }, ClassReader.SKIP_CODE );
          assertEquals( "__StringUtils.java", source[0] );
        }
    }

    @Test
    public void testShaderWithNestedJar() throws Exception
    {
        TemporaryFolder temporaryFolder = new TemporaryFolder();

        final String innerJarFileName = "inner.jar";

        temporaryFolder.create();
        File innerJar = temporaryFolder.newFile( innerJarFileName );
        try ( JarOutputStream jos = new JarOutputStream( new FileOutputStream( innerJar ) ) )
        {
            jos.putNextEntry( new JarEntry( "foo.txt" ) );
            jos.write( "c1".getBytes( StandardCharsets.UTF_8 ) );
            jos.closeEntry();
        }

        File outerJar = temporaryFolder.newFile( "outer.jar" );
        try ( JarOutputStream jos = new JarOutputStream( new FileOutputStream( outerJar ) ) )
        {
            FileInputStream innerStream = new FileInputStream( innerJar );
            byte[] bytes = IOUtil.toByteArray( innerStream, 32 * 1024 );
            innerStream.close();
            writeEntryWithoutCompression( innerJarFileName, bytes, jos );
        }


        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( new LinkedHashSet<>( Collections.singleton( outerJar ) ) );
        shadeRequest.setFilters( new ArrayList<Filter>() );
        shadeRequest.setRelocators( new ArrayList<Relocator>() );
        shadeRequest.setResourceTransformers( new ArrayList<ResourceTransformer>() );
        File shadedFile = temporaryFolder.newFile( "shaded.jar" );
        shadeRequest.setUberJar( shadedFile );

        DefaultShader shader = newShader();
        shader.shade( shadeRequest );

        JarFile shadedJarFile = new JarFile( shadedFile );
        JarEntry entry = shadedJarFile.getJarEntry( innerJarFileName );

        //After shading, entry compression method should not be changed.
        Assert.assertEquals( entry.getMethod(), ZipEntry.STORED );

        temporaryFolder.delete();
    }

    private void writeEntryWithoutCompression( String entryName, byte[] entryBytes, JarOutputStream jos ) throws IOException
    {
        final JarEntry entry = new JarEntry( entryName );
        final int size = entryBytes.length;
        final CRC32 crc = new CRC32();
        crc.update( entryBytes, 0, size );
        entry.setSize( size );
        entry.setCompressedSize( size );
        entry.setMethod( ZipEntry.STORED );
        entry.setCrc( crc.getValue() );
        jos.putNextEntry( entry );
        jos.write( entryBytes );
        jos.closeEntry();
    }

    private void shaderWithPattern( String shadedPattern, File jar, String[] excludes )
        throws Exception
    {
        DefaultShader s = newShader();

        Set<File> set = new LinkedHashSet<>();

        set.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util", shadedPattern, Collections.emptyList(), Arrays.asList( excludes ) ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add( new ComponentsXmlResourceTransformer() );

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( jar );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );

        s.shade( shadeRequest );
    }

    private DefaultShader newShader()
    {
        return new DefaultShader(mockLogger());
    }

    private ArgumentCaptor<String> debugMessages;

    private ArgumentCaptor<String> warnMessages;

    private Logger mockLogger()
    {
        debugMessages = ArgumentCaptor.forClass(String.class);
        warnMessages = ArgumentCaptor.forClass(String.class);
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);
        doNothing().when(logger).debug(debugMessages.capture());
        doNothing().when(logger).warn(warnMessages.capture());
        return logger;
    }

    private boolean areEqual( final JarFile jar1, final JarFile jar2, final String entry ) throws IOException
    {
        return areEqual( jar1, jar2, entry, entry );
    }

    private boolean areEqual( final JarFile jar1, final JarFile jar2, final String entry1, String entry2 )
        throws IOException
    {
        try ( final InputStream s1 = jar1.getInputStream(
                requireNonNull(jar1.getJarEntry(entry1), entry1 + " in " + jar1.getName() ) );
              final InputStream s2 = jar2.getInputStream(
                      requireNonNull(jar2.getJarEntry(entry2), entry2 + " in " + jar2.getName() ) ))
        {
            return Arrays.equals( IOUtil.toByteArray( s1 ), IOUtil.toByteArray( s2 ) );
        }
    }
}
