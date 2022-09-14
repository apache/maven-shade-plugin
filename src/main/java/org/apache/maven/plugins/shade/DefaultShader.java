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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ReproducibleResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.codehaus.plexus.util.IOUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jason van Zyl
 */
@Singleton
@Named
public class DefaultShader
    implements Shader
{
    private static final int BUFFER_SIZE = 32 * 1024;

    private final Logger logger;

    public DefaultShader()
    {
        this( LoggerFactory.getLogger( DefaultShader.class ) );
    }

    public DefaultShader( final Logger logger )
    {
        this.logger = Objects.requireNonNull( logger );
    }

    public void shade( ShadeRequest shadeRequest )
        throws IOException, MojoExecutionException
    {
        Set<String> resources = new HashSet<>();

        ManifestResourceTransformer manifestTransformer = null;
        List<ResourceTransformer> transformers =
            new ArrayList<>( shadeRequest.getResourceTransformers() );
        for ( Iterator<ResourceTransformer> it = transformers.iterator(); it.hasNext(); )
        {
            ResourceTransformer transformer = it.next();
            if ( transformer instanceof ManifestResourceTransformer )
            {
                manifestTransformer = (ManifestResourceTransformer) transformer;
                it.remove();
            }
        }

        final DefaultPackageMapper packageMapper = new DefaultPackageMapper( shadeRequest.getRelocators() );

        // noinspection ResultOfMethodCallIgnored
        shadeRequest.getUberJar().getParentFile().mkdirs();

        try ( JarOutputStream out  =
                  new JarOutputStream( new BufferedOutputStream( new FileOutputStream( shadeRequest.getUberJar() ) ) ) )
        {
            goThroughAllJarEntriesForManifestTransformer( shadeRequest, resources, manifestTransformer, out );

            // CHECKSTYLE_OFF: MagicNumber
            MultiValuedMap<String, File> duplicates = new HashSetValuedHashMap<>( 10000, 3 );
            // CHECKSTYLE_ON: MagicNumber

            shadeJars( shadeRequest, resources, transformers, out, duplicates, packageMapper );

            // CHECKSTYLE_OFF: MagicNumber
            MultiValuedMap<Collection<File>, String> overlapping = new HashSetValuedHashMap<>( 20, 15 );
            // CHECKSTYLE_ON: MagicNumber

            for ( String clazz : duplicates.keySet() )
            {
                Collection<File> jarz = duplicates.get( clazz );
                if ( jarz.size() > 1 )
                {
                    overlapping.put( jarz, clazz );
                }
            }

            // Log a summary of duplicates
            logSummaryOfDuplicates( overlapping );

            if ( overlapping.keySet().size() > 0 )
            {
                showOverlappingWarning();
            }

            for ( ResourceTransformer transformer : transformers )
            {
                if ( transformer.hasTransformedResource() )
                {
                    transformer.modifyOutputStream( out );
                }
            }
        }

        for ( Filter filter : shadeRequest.getFilters() )
        {
            filter.finished();
        }
    }

    /**
     * {@link InputStream} that can peek ahead at zip header bytes.
     */
    private static class ZipHeaderPeekInputStream extends PushbackInputStream
    {

        private static final byte[] ZIP_HEADER = new byte[] {0x50, 0x4b, 0x03, 0x04};

        private static final int HEADER_LEN = 4;

        protected ZipHeaderPeekInputStream( InputStream in )
        {
            super( in, HEADER_LEN );
        }

        public boolean hasZipHeader() throws IOException
        {
            final byte[] header = new byte[HEADER_LEN];
            super.read( header, 0, HEADER_LEN );
            super.unread( header );
            return Arrays.equals( header, ZIP_HEADER );
        }
    }

    /**
     * Data holder for CRC and Size.
     */
    private static class CrcAndSize
    {

        private final CRC32 crc = new CRC32();

        private long size;

        CrcAndSize( InputStream inputStream ) throws IOException
        {
            load( inputStream );
        }

        private void load( InputStream inputStream ) throws IOException
        {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ( ( bytesRead = inputStream.read( buffer ) ) != -1 )
            {
                this.crc.update( buffer, 0, bytesRead );
                this.size += bytesRead;
            }
        }

        public void setupStoredEntry( JarEntry entry )
        {
            entry.setSize( this.size );
            entry.setCompressedSize( this.size );
            entry.setCrc( this.crc.getValue() );
            entry.setMethod( ZipEntry.STORED );
        }
    }

    private void shadeJars( ShadeRequest shadeRequest, Set<String> resources, List<ResourceTransformer> transformers,
                            JarOutputStream jos, MultiValuedMap<String, File> duplicates,
                            DefaultPackageMapper packageMapper )
        throws IOException
    {
        for ( File jar : shadeRequest.getJars() )
        {

            logger.debug( "Processing JAR " + jar );

            List<Filter> jarFilters = getFilters( jar, shadeRequest.getFilters() );

            try ( JarFile jarFile = newJarFile( jar ) )
            {

                for ( Enumeration<JarEntry> j = jarFile.entries(); j.hasMoreElements(); )
                {
                    JarEntry entry = j.nextElement();

                    String name = entry.getName();
                    
                    if ( entry.isDirectory() || isFiltered( jarFilters, name ) )
                    {
                        continue;
                    }


                    if ( "META-INF/INDEX.LIST".equals( name ) )
                    {
                        // we cannot allow the jar indexes to be copied over or the
                        // jar is useless. Ideally, we could create a new one
                        // later
                        continue;
                    }

                    if ( "module-info.class".equals( name ) )
                    {
                        logger.warn( "Discovered module-info.class. "
                            + "Shading will break its strong encapsulation." );
                        continue;
                    }

                    try
                    {
                        shadeJarEntry( shadeRequest, resources, transformers, packageMapper, jos, duplicates, jar,
                                        jarFile, entry, name );
                    }
                    catch ( Exception e )
                    {
                        throw new IOException( String.format( "Problem shading JAR %s entry %s: %s", jar, name, e ),
                                               e );
                    }
                }

            }
        }
    }

    private void shadeJarEntry( ShadeRequest shadeRequest, Set<String> resources,
                                 List<ResourceTransformer> transformers, DefaultPackageMapper packageMapper,
                                 JarOutputStream jos, MultiValuedMap<String, File> duplicates, File jar,
                                 JarFile jarFile, JarEntry entry, String name )
        throws IOException, MojoExecutionException
    {
        try ( InputStream in = jarFile.getInputStream( entry ) )
        {
            String mappedName = packageMapper.map( name, true, false );

            int idx = mappedName.lastIndexOf( '/' );
            if ( idx != -1 )
            {
                // make sure dirs are created
                String dir = mappedName.substring( 0, idx );
                if ( !resources.contains( dir ) )
                {
                    addDirectory( resources, jos, dir, entry.getTime() );
                }
            }

            duplicates.put( name, jar );
            if ( name.endsWith( ".class" ) )
            {
                addRemappedClass( jos, jar, name, entry.getTime(), in, packageMapper );
            }
            else if ( shadeRequest.isShadeSourcesContent() && name.endsWith( ".java" ) )
            {
                // Avoid duplicates
                if ( resources.contains( mappedName ) )
                {
                    return;
                }

                addJavaSource( resources, jos, mappedName, entry.getTime(), in, shadeRequest.getRelocators() );
            }
            else
            {
                if ( !resourceTransformed( transformers, mappedName, in, shadeRequest.getRelocators(),
                                           entry.getTime() ) )
                {
                    // Avoid duplicates that aren't accounted for by the resource transformers
                    if ( resources.contains( mappedName ) )
                    {
                        logger.debug( "We have a duplicate " + name + " in " + jar );
                        return;
                    }

                    addResource( resources, jos, mappedName, entry, jarFile );
                }
                else
                {
                    duplicates.removeMapping( name, jar );
                }
            }
        }
    }

    private void goThroughAllJarEntriesForManifestTransformer( ShadeRequest shadeRequest, Set<String> resources,
                                                               ManifestResourceTransformer manifestTransformer,
                                                               JarOutputStream jos )
        throws IOException
    {
        if ( manifestTransformer == null )
        {
            return;
        }

        for ( File jar : shadeRequest.getJars() )
        {
            try ( JarFile jarFile = newJarFile( jar ) )
            {
                for ( Enumeration<JarEntry> en = jarFile.entries(); en.hasMoreElements(); )
                {
                    JarEntry entry = en.nextElement();
                    String resource = entry.getName();
                    if ( manifestTransformer.canTransformResource( resource ) )
                    {
                        resources.add( resource );
                        try ( InputStream inputStream = jarFile.getInputStream( entry ) )
                        {
                            manifestTransformer.processResource( resource, inputStream,
                                                                 shadeRequest.getRelocators(), entry.getTime() );
                        }
                        break;
                    }
                }
            }
        }
        if ( manifestTransformer.hasTransformedResource() )
        {
            manifestTransformer.modifyOutputStream( jos );
        }
    }

    private void showOverlappingWarning()
    {
        logger.warn( "maven-shade-plugin has detected that some class files are" );
        logger.warn( "present in two or more JARs. When this happens, only one" );
        logger.warn( "single version of the class is copied to the uber jar." );
        logger.warn( "Usually this is not harmful and you can skip these warnings," );
        logger.warn( "otherwise try to manually exclude artifacts based on" );
        logger.warn( "mvn dependency:tree -Ddetail=true and the above output." );
        logger.warn( "See https://maven.apache.org/plugins/maven-shade-plugin/" );
    }

    private void logSummaryOfDuplicates( MultiValuedMap<Collection<File>, String> overlapping )
    {
        for ( Collection<File> jarz : overlapping.keySet() )
        {
            List<String> jarzS = jarz.stream()
                    .map( File::getName )
                    .sorted()
                    .collect( Collectors.toList() );

            List<String> classes = new LinkedList<>();
            List<String> resources = new LinkedList<>();

            for ( String name : overlapping.get( jarz ) )
            {
                if ( name.endsWith( ".class" ) )
                {
                    classes.add( name.replace( ".class", "" ).replace( "/", "." ) );
                }
                else
                {
                    resources.add( name );
                }
            }

            final Collection<String> overlaps = new ArrayList<>();
            if ( !classes.isEmpty() )
            {
                if ( resources.size() == 1 )
                {
                    overlaps.add( "class" );
                }
                else
                {
                    overlaps.add( "classes" );
                }
            }
            if ( !resources.isEmpty() )
            {
                if ( resources.size() == 1 )
                {
                    overlaps.add( "resource" );
                }
                else
                {
                    overlaps.add( "resources" );
                }
            }

            final List<String> all = new ArrayList<>( classes.size() + resources.size() );
            all.addAll( classes );
            all.addAll( resources );

            logger.warn(
                    String.join( ", ", jarzS ) + " define " + all.size()
                            + " overlapping " + String.join( " and ", overlaps ) + ": " );

            Collections.sort( all );

            int max = 10;

            for ( int i = 0; i < Math.min( max, all.size() ); i++ )
            {
                logger.warn( "  - " + all.get( i ) );
            }

            if ( all.size() > max )
            {
                logger.warn( "  - " + ( all.size() - max ) + " more..." );
            }

        }
    }

    private JarFile newJarFile( File jar )
        throws IOException
    {
        try
        {
            return new JarFile( jar );
        }
        catch ( ZipException zex )
        {
            // JarFile is not very verbose and doesn't tell the user which file it was
            // so we will create a new Exception instead
            throw new ZipException( "error in opening zip file " + jar );
        }
    }

    private List<Filter> getFilters( File jar, List<Filter> filters )
    {
        List<Filter> list = new ArrayList<>();

        for ( Filter filter : filters )
        {
            if ( filter.canFilter( jar ) )
            {
                list.add( filter );
            }

        }

        return list;
    }

    private void addDirectory( Set<String> resources, JarOutputStream jos, String name, long time )
        throws IOException
    {
        if ( name.lastIndexOf( '/' ) > 0 )
        {
            String parent = name.substring( 0, name.lastIndexOf( '/' ) );
            if ( !resources.contains( parent ) )
            {
                addDirectory( resources, jos, parent, time );
            }
        }

        // directory entries must end in "/"
        JarEntry entry = new JarEntry( name + "/" );
        entry.setTime( time );
        jos.putNextEntry( entry );

        resources.add( name );
    }

    private void addRemappedClass( JarOutputStream jos, File jar, String name,
                                   long time, InputStream is, DefaultPackageMapper packageMapper )
        throws IOException, MojoExecutionException
    {
        if ( packageMapper.relocators.isEmpty() )
        {
            try
            {
                JarEntry entry = new JarEntry( name );
                entry.setTime( time );
                jos.putNextEntry( entry );
                IOUtil.copy( is, jos );
            }
            catch ( ZipException e )
            {
                logger.debug( "We have a duplicate " + name + " in " + jar );
            }

            return;
        }
        
        // Keep the original class in, in case nothing was relocated by RelocatorRemapper. This avoids binary
        // differences between classes, simply because they were rewritten and only details like constant pool or
        // stack map frames are slightly different.
        byte[] originalClass = IOUtil.toByteArray( is );
        
        ClassReader cr = new ClassReader( new ByteArrayInputStream( originalClass ) );

        // We don't pass the ClassReader here. This forces the ClassWriter to rebuild the constant pool.
        // Copying the original constant pool should be avoided because it would keep references
        // to the original class names. This is not a problem at runtime (because these entries in the
        // constant pool are never used), but confuses some tools such as Felix' maven-bundle-plugin
        // that use the constant pool to determine the dependencies of a class.
        ClassWriter cw = new ClassWriter( 0 );

        final String pkg = name.substring( 0, name.lastIndexOf( '/' ) + 1 );
        final ShadeClassRemapper cv = new ShadeClassRemapper( cw, pkg, packageMapper );

        try
        {
            cr.accept( cv, ClassReader.EXPAND_FRAMES );
        }
        catch ( Throwable ise )
        {
            throw new MojoExecutionException( "Error in ASM processing class " + name, ise );
        }

        // If nothing was relocated by RelocatorRemapper, write the original class, otherwise the transformed one
        final byte[] renamedClass;
        if ( cv.remapped )
        {
            logger.debug( "Rewrote class bytecode: " + name );
            renamedClass = cw.toByteArray();
        }
        else
        {
            logger.debug( "Keeping original class bytecode: " + name );
            renamedClass = originalClass;
        }

        // Need to take the .class off for remapping evaluation
        String mappedName = packageMapper.map( name.substring( 0, name.indexOf( '.' ) ), true, false );

        try
        {
            // Now we put it back on so the class file is written out with the right extension.
            JarEntry entry = new JarEntry( mappedName + ".class" );
            entry.setTime( time );
            jos.putNextEntry( entry );

            jos.write( renamedClass );
        }
        catch ( ZipException e )
        {
            logger.debug( "We have a duplicate " + mappedName + " in " + jar );
        }
    }

    private boolean isFiltered( List<Filter> filters, String name )
    {
        for ( Filter filter : filters )
        {
            if ( filter.isFiltered( name ) )
            {
                return true;
            }
        }

        return false;
    }

    private boolean resourceTransformed( List<ResourceTransformer> resourceTransformers, String name, InputStream is,
                                         List<Relocator> relocators, long time )
        throws IOException
    {
        boolean resourceTransformed = false;

        for ( ResourceTransformer transformer : resourceTransformers )
        {
            if ( transformer.canTransformResource( name ) )
            {
                logger.debug( "Transforming " + name + " using " + transformer.getClass().getName() );

                if ( transformer instanceof ReproducibleResourceTransformer )
                {
                    ( (ReproducibleResourceTransformer) transformer ).processResource( name, is, relocators, time );
                }
                else
                {
                    transformer.processResource( name, is, relocators );
                }

                resourceTransformed = true;

                break;
            }
        }
        return resourceTransformed;
    }

    private void addJavaSource( Set<String> resources, JarOutputStream jos, String name, long time, InputStream is,
                                List<Relocator> relocators )
        throws IOException
    {
        JarEntry entry = new JarEntry( name );
        entry.setTime( time );
        jos.putNextEntry( entry );

        String sourceContent = IOUtil.toString( new InputStreamReader( is, StandardCharsets.UTF_8 ) );

        for ( Relocator relocator : relocators )
        {
            sourceContent = relocator.applyToSourceContent( sourceContent );
        }

        final Writer writer = new OutputStreamWriter( jos, StandardCharsets.UTF_8 );
        writer.write( sourceContent );
        writer.flush();

        resources.add( name );
    }

    private void addResource( Set<String> resources, JarOutputStream jos, String name, JarEntry originalEntry,
                              JarFile jarFile ) throws IOException
    {
        ZipHeaderPeekInputStream inputStream = new ZipHeaderPeekInputStream( jarFile.getInputStream( originalEntry ) );
        try
        {
            final JarEntry entry = new JarEntry( name );

            // We should not change compressed level of uncompressed entries, otherwise JVM can't load these nested jars
            if ( inputStream.hasZipHeader() && originalEntry.getMethod() == ZipEntry.STORED )
            {
                new CrcAndSize( inputStream ).setupStoredEntry( entry );
                inputStream.close();
                inputStream = new ZipHeaderPeekInputStream( jarFile.getInputStream( originalEntry ) );
            }


            entry.setTime( originalEntry.getTime() );

            jos.putNextEntry( entry );

            IOUtil.copy( inputStream, jos );

            resources.add( name );
        }
        finally
        {
            inputStream.close();
        }
    }

    private interface PackageMapper
    {
        /**
         * Map an entity name according to the mapping rules known to this package mapper
         * 
         * @param entityName entity name to be mapped
         * @param mapPaths map "slashy" names like paths or internal Java class names, e.g. {@code com/acme/Foo}?
         * @param mapPackages  map "dotty" names like qualified Java class or package names, e.g. {@code com.acme.Foo}?
         * @return mapped entity name, e.g. {@code org/apache/acme/Foo} or {@code org.apache.acme.Foo}
         */
        String map( String entityName, boolean mapPaths, boolean mapPackages );
    }

    /**
     * A package mapper based on a list of {@link Relocator}s
     */
    private static class DefaultPackageMapper implements PackageMapper
    {
        private static final Pattern CLASS_PATTERN = Pattern.compile( "(\\[*)?L(.+);" );

        private final List<Relocator> relocators;

        private DefaultPackageMapper( final List<Relocator> relocators )
        {
            this.relocators = relocators;
        }

        @Override
        public String map( String entityName, boolean mapPaths, final boolean mapPackages )
        {
            String value = entityName;

            String prefix = "";
            String suffix = "";

            Matcher m = CLASS_PATTERN.matcher( entityName );
            if ( m.matches() )
            {
                prefix = m.group( 1 ) + "L";
                suffix = ";";
                entityName = m.group( 2 );
            }

            for ( Relocator r : relocators )
            {
                if ( mapPackages && r.canRelocateClass( entityName ) )
                {
                    value = prefix + r.relocateClass( entityName ) + suffix;
                    break;
                }
                else if ( mapPaths && r.canRelocatePath( entityName ) )
                {
                    value = prefix + r.relocatePath( entityName ) + suffix;
                    break;
                }
            }
            return value;
        }
    }

    private static class LazyInitRemapper extends Remapper
    {
        private PackageMapper relocators;

        @Override
        public Object mapValue( Object object )
        {
            return object instanceof String
                    ? relocators.map( (String) object, true, true )
                    : super.mapValue( object );
        }

        @Override
        public String map( String name )
        {
            // NOTE: Before the factoring out duplicate code from 'private String map(String, boolean)', this method did
            // the same as 'mapValue', except for not trying to replace "dotty" package-like patterns (only "slashy"
            // path-like ones). The refactoring retains this difference. But actually, all unit and integration tests
            // still pass, if both variants are unified into one which always tries to replace both pattern types.
            //
            //  TODO: Analyse if this case is really necessary and has any special meaning or avoids any known problems.
            //   If not, then simplify DefaultShader.PackageMapper.map to only have the String parameter and assume
            //   both boolean ones to always be true.
            return relocators.map( name, true, false );
        }
    }

    // TODO: we can avoid LazyInitRemapper N instantiations (and use a singleton)
    //       reimplementing ClassRemapper there.
    //       It looks a bad idea but actually enables us to respect our relocation API which has no
    //       consistency with ASM one which can lead to multiple issues for short relocation patterns
    //       plus overcome ClassRemapper limitations we can care about (see its javadoc for details).
    //
    // NOTE: very short term we can just reuse the same LazyInitRemapper and let the constructor set it.
    //       since multithreading is not faster in this processing it would be more than sufficient if
    //       caring of this 2 objects per class allocation (but keep in mind the visitor will allocate way more ;)).
    //       Last point which makes it done this way as of now is that perf seems not impacted at all.
    private static class ShadeClassRemapper extends ClassRemapper implements PackageMapper
    {
        private final String pkg;
        private final PackageMapper packageMapper;
        private boolean remapped;

        ShadeClassRemapper( final ClassVisitor classVisitor, final String pkg,
                            final DefaultPackageMapper packageMapper )
        {
            super( classVisitor, new LazyInitRemapper() /* can't be init in the constructor with "this" */ );
            this.pkg = pkg;
            this.packageMapper = packageMapper;

            // use this to enrich relocators impl with "remapped" logic
            LazyInitRemapper.class.cast( remapper ).relocators = this;
        }

        @Override
        public void visitSource( final String source, final String debug )
        {
            if ( source == null )
            {
                super.visitSource( null, debug );
                return;
            }

            final String fqSource = pkg + source;
            final String mappedSource = map( fqSource, true, false );
            final String filename = mappedSource.substring( mappedSource.lastIndexOf( '/' ) + 1 );
            super.visitSource( filename, debug );
        }

        @Override
        public String map( final String entityName, boolean mapPaths, final boolean mapPackages )
        {
            final String mapped = packageMapper.map( entityName, true, mapPackages );
            if ( !remapped )
            {
                remapped = !mapped.equals( entityName );
            }
            return mapped;
        }
    }
}
