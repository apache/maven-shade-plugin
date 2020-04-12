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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.IOUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * @author Jason van Zyl
 */
@Component( role = Shader.class, hint = "default" )
public class DefaultShader
    extends AbstractLogEnabled
    implements Shader
{

    public void shade( ShadeRequest shadeRequest )
        throws IOException, MojoExecutionException
    {
        Set<String> resources = new HashSet<>();

        ResourceTransformer manifestTransformer = null;
        List<ResourceTransformer> transformers =
            new ArrayList<>( shadeRequest.getResourceTransformers() );
        for ( Iterator<ResourceTransformer> it = transformers.iterator(); it.hasNext(); )
        {
            ResourceTransformer transformer = it.next();
            if ( transformer instanceof ManifestResourceTransformer )
            {
                manifestTransformer = transformer;
                it.remove();
            }
        }

        RelocatorRemapper remapper = new RelocatorRemapper( shadeRequest.getRelocators() );

        // noinspection ResultOfMethodCallIgnored
        shadeRequest.getUberJar().getParentFile().mkdirs();

        JarOutputStream out = null;
        try
        {
            out = new JarOutputStream( new BufferedOutputStream( new FileOutputStream( shadeRequest.getUberJar() ) ) );
            goThroughAllJarEntriesForManifestTransformer( shadeRequest, resources, manifestTransformer, out );

            // CHECKSTYLE_OFF: MagicNumber
            Multimap<String, File> duplicates = HashMultimap.create( 10000, 3 );
            // CHECKSTYLE_ON: MagicNumber

            shadeJars( shadeRequest, resources, transformers, remapper, out, duplicates );

            // CHECKSTYLE_OFF: MagicNumber
            Multimap<Collection<File>, String> overlapping = HashMultimap.create( 20, 15 );
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

            out.close();
            out = null;
        }
        finally
        {
            IOUtil.close( out );
        }

        for ( Filter filter : shadeRequest.getFilters() )
        {
            filter.finished();
        }
    }

    private void shadeJars( ShadeRequest shadeRequest, Set<String> resources, List<ResourceTransformer> transformers,
                            RelocatorRemapper remapper, JarOutputStream jos, Multimap<String, File> duplicates )
        throws IOException, MojoExecutionException
    {
        for ( File jar : shadeRequest.getJars() )
        {

            getLogger().debug( "Processing JAR " + jar );

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
                        getLogger().warn( "Discovered module-info.class. "
                            + "Shading will break its strong encapsulation." );
                        continue;
                    }

                    try
                    {
                        shadeSingleJar( shadeRequest, resources, transformers, remapper, jos, duplicates, jar,
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

    private void shadeSingleJar( ShadeRequest shadeRequest, Set<String> resources,
                                 List<ResourceTransformer> transformers, RelocatorRemapper remapper,
                                 JarOutputStream jos, Multimap<String, File> duplicates, File jar, JarFile jarFile,
                                 JarEntry entry, String name )
        throws IOException, MojoExecutionException
    {
        try ( InputStream in = jarFile.getInputStream( entry ) )
        {
            String mappedName = remapper.map( name );

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
                addRemappedClass( remapper, jos, jar, name, entry.getTime(), in );
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
                        getLogger().debug( "We have a duplicate " + name + " in " + jar );
                        return;
                    }

                    addResource( resources, jos, mappedName, entry.getTime(), in );
                }
            }
        }
    }

    private void goThroughAllJarEntriesForManifestTransformer( ShadeRequest shadeRequest, Set<String> resources,
                                                               ResourceTransformer resourceTransformer,
                                                               JarOutputStream jos )
        throws IOException
    {
        if ( resourceTransformer != null )
        {
            for ( File jar : shadeRequest.getJars() )
            {
                try ( JarFile jarFile = newJarFile( jar ) )
                {
                    for ( Enumeration<JarEntry> en = jarFile.entries(); en.hasMoreElements(); )
                    {
                        JarEntry entry = en.nextElement();
                        String resource = entry.getName();
                        if ( resourceTransformer.canTransformResource( resource ) )
                        {
                            resources.add( resource );
                            try ( InputStream inputStream = jarFile.getInputStream( entry ) )
                            {
                                resourceTransformer.processResource( resource, inputStream,
                                        shadeRequest.getRelocators(), entry.getTime() );
                            }
                            break;
                        }
                    }
                }
            }
            if ( resourceTransformer.hasTransformedResource() )
            {
                resourceTransformer.modifyOutputStream( jos );
            }
        }
    }

    private void showOverlappingWarning()
    {
        getLogger().warn( "maven-shade-plugin has detected that some class files are" );
        getLogger().warn( "present in two or more JARs. When this happens, only one" );
        getLogger().warn( "single version of the class is copied to the uber jar." );
        getLogger().warn( "Usually this is not harmful and you can skip these warnings," );
        getLogger().warn( "otherwise try to manually exclude artifacts based on" );
        getLogger().warn( "mvn dependency:tree -Ddetail=true and the above output." );
        getLogger().warn( "See http://maven.apache.org/plugins/maven-shade-plugin/" );
    }

    private void logSummaryOfDuplicates( Multimap<Collection<File>, String> overlapping )
    {
        for ( Collection<File> jarz : overlapping.keySet() )
        {
            List<String> jarzS = new ArrayList<>();

            for ( File jjar : jarz )
            {
                jarzS.add( jjar.getName() );
            }

            Collections.sort( jarzS ); // deterministic messages to be able to compare outputs (useful on CI)

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

            //CHECKSTYLE_OFF: LineLength
            final Collection<String> overlaps = new ArrayList<>();
            if ( !classes.isEmpty() )
            {
                overlaps.add( "classes" );
            }
            if ( !resources.isEmpty() )
            {
                overlaps.add( "resources" );
            }

            final List<String> all = new ArrayList<>( classes.size() + resources.size() );
            all.addAll( classes );
            all.addAll( resources );

            getLogger().warn(
                StringUtils.join( jarzS, ", " ) + " define " + all.size()
                + " overlapping " + StringUtils.join( overlaps, " and " ) + ": " );
            //CHECKSTYLE_ON: LineLength

            Collections.sort( all );

            int max = 10;

            for ( int i = 0; i < Math.min( max, all.size() ); i++ )
            {
                getLogger().warn( "  - " + all.get( i ) );
            }

            if ( all.size() > max )
            {
                getLogger().warn( "  - " + ( all.size() - max ) + " more..." );
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

    private void addRemappedClass( RelocatorRemapper remapper, JarOutputStream jos, File jar, String name,
                                   long time, InputStream is )
        throws IOException, MojoExecutionException
    {
        if ( !remapper.hasRelocators() )
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
                getLogger().debug( "We have a duplicate " + name + " in " + jar );
            }

            return;
        }

        ClassReader cr = new ClassReader( is );

        // We don't pass the ClassReader here. This forces the ClassWriter to rebuild the constant pool.
        // Copying the original constant pool should be avoided because it would keep references
        // to the original class names. This is not a problem at runtime (because these entries in the
        // constant pool are never used), but confuses some tools such as Felix' maven-bundle-plugin
        // that use the constant pool to determine the dependencies of a class.
        ClassWriter cw = new ClassWriter( 0 );

        final String pkg = name.substring( 0, name.lastIndexOf( '/' ) + 1 );
        ClassVisitor cv = new ClassRemapper( cw, remapper )
        {
            @Override
            public void visitSource( final String source, final String debug )
            {
                if ( source == null )
                {
                    super.visitSource( source, debug );
                }
                else
                {
                    final String fqSource = pkg + source;
                    final String mappedSource = remapper.map( fqSource );
                    final String filename = mappedSource.substring( mappedSource.lastIndexOf( '/' ) + 1 );
                    super.visitSource( filename, debug );
                }
            }
        };

        try
        {
            cr.accept( cv, ClassReader.EXPAND_FRAMES );
        }
        catch ( Throwable ise )
        {
            throw new MojoExecutionException( "Error in ASM processing class " + name, ise );
        }

        byte[] renamedClass = cw.toByteArray();

        // Need to take the .class off for remapping evaluation
        String mappedName = remapper.map( name.substring( 0, name.indexOf( '.' ) ) );

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
            getLogger().debug( "We have a duplicate " + mappedName + " in " + jar );
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
                getLogger().debug( "Transforming " + name + " using " + transformer.getClass().getName() );

                transformer.processResource( name, is, relocators, time );

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

    private void addResource( Set<String> resources, JarOutputStream jos, String name, long time,
                              InputStream is )
        throws IOException
    {
        final JarEntry entry = new JarEntry( name );

        entry.setTime( time );

        jos.putNextEntry( entry );

        IOUtil.copy( is, jos );

        resources.add( name );
    }

    static class RelocatorRemapper
        extends Remapper
    {

        private final Pattern classPattern = Pattern.compile( "(\\[*)?L(.+);" );

        List<Relocator> relocators;

        RelocatorRemapper( List<Relocator> relocators )
        {
            this.relocators = relocators;
        }

        public boolean hasRelocators()
        {
            return !relocators.isEmpty();
        }

        public Object mapValue( Object object )
        {
            if ( object instanceof String )
            {
                String name = (String) object;
                String value = name;

                String prefix = "";
                String suffix = "";

                Matcher m = classPattern.matcher( name );
                if ( m.matches() )
                {
                    prefix = m.group( 1 ) + "L";
                    suffix = ";";
                    name = m.group( 2 );
                }

                for ( Relocator r : relocators )
                {
                    if ( r.canRelocateClass( name ) )
                    {
                        value = prefix + r.relocateClass( name ) + suffix;
                        break;
                    }
                    else if ( r.canRelocatePath( name ) )
                    {
                        value = prefix + r.relocatePath( name ) + suffix;
                        break;
                    }
                }

                return value;
            }

            return super.mapValue( object );
        }

        public String map( String name )
        {
            String value = name;

            String prefix = "";
            String suffix = "";

            Matcher m = classPattern.matcher( name );
            if ( m.matches() )
            {
                prefix = m.group( 1 ) + "L";
                suffix = ";";
                name = m.group( 2 );
            }

            for ( Relocator r : relocators )
            {
                if ( r.canRelocatePath( name ) )
                {
                    value = prefix + r.relocatePath( name ) + suffix;
                    break;
                }
            }

            return value;
        }

    }

}
