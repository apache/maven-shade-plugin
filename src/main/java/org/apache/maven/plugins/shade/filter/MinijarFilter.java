package org.apache.maven.plugins.shade.filter;

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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.IOUtil;
import org.vafer.jdependency.Clazz;
import org.vafer.jdependency.Clazzpath;
import org.vafer.jdependency.ClazzpathUnit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

/**
 * A filter that prevents the inclusion of classes not required in the final jar.
 *
 * @author Torsten Curdt
 */
public class MinijarFilter
    implements Filter
{

    private Log log;

    private Set<Clazz> removable;

    private int classesKept;

    private int classesRemoved;

    //[MSHADE-209] This is introduced only for testing purposes which shows
    // there is something wrong with the design of this class. (SoC?)
    // unfortunately i don't have a better idea at the moment.
    MinijarFilter( int classesKept, int classesRemoved, Log log )
    {
        this.classesKept = classesKept;
        this.classesRemoved = classesRemoved;
        this.log = log;
    }

    /**
     * @param project {@link MavenProject}
     * @param log {@link Log}
     * @throws IOException in case of error.
     */
    public MinijarFilter( MavenProject project, Log log )
        throws IOException
    {
        this( project, log, Collections.<SimpleFilter>emptyList(), false );
    }

    /**
     * @param project {@link MavenProject}
     * @param log {@link Log}
     * @param simpleFilters {@link SimpleFilter}
     * @param keepServices don't eliminate classes needed by {@code META-INF/services}
     * @throws IOException in case of errors.
     * @since 1.6
     */
    public MinijarFilter( MavenProject project, Log log, List<SimpleFilter> simpleFilters, final boolean keepServices )
        throws IOException
    {
      this.log = log;

      File artifactFile = project.getArtifact().getFile();

        if ( artifactFile != null )
        {
          Clazzpath cp = new Clazzpath();

          ClazzpathUnit artifactUnit = cp.addClazzpathUnit( new FileInputStream( artifactFile ), project.toString() );

            for ( Artifact dependency : project.getArtifacts() )
            {
                addDependencyToClasspath( cp, dependency );
            }

            removable = cp.getClazzes();
            if ( removable.remove( new Clazz( "module-info" ) ) )
            {
                log.warn( "Removing module-info from " + artifactFile.getName() );
            }
            removePackages( artifactUnit );
            removable.removeAll( artifactUnit.getClazzes() );
            removable.removeAll( artifactUnit.getTransitiveDependencies() );
            removeSpecificallyIncludedClasses( project,
                simpleFilters == null ? Collections.<SimpleFilter>emptyList() : simpleFilters );

            if ( keepServices )
            {
                removeServices( project, cp );
            }
        }
    }

    private void removeServices( final MavenProject project, final Clazzpath cp )
    {
        try
        {
            for ( final String fileName : project.getRuntimeClasspathElements() )
            {
                try ( final JarFile jar = new JarFile( fileName ) )
                {
                    for ( final Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); )
                    {
                        final JarEntry jarEntry = entries.nextElement();
                        if ( !jarEntry.getName().startsWith( "META-INF/services/" ) )
                        {
                            continue;
                        }

                        try ( final BufferedReader bufferedReader =
                           new BufferedReader( new InputStreamReader( jar.getInputStream( jarEntry ) ) ) )
                        {
                            for ( String className = bufferedReader.readLine(); className != null;
                                className = bufferedReader.readLine() )
                            {
                               log.info( className + " was not removed because it is a service" );
                               removeClass( cp , className );
                            }
                        }
                        catch ( final IOException e )
                        {
                            log.warn( e.getMessage() );
                        }
                    }
                }
                catch ( final IOException e )
                {
                    log.warn( e.getMessage() );
                }
            }
        }
        catch ( final DependencyResolutionRequiredException e )
        {
            log.warn( e.getMessage() );
        }
    }

    private void removeClass( final Clazzpath clazzPath, final String className )
    {
        final Clazz clazz = clazzPath.getClazz( className );
        removable.remove( clazz );
        removable.removeAll( clazz.getTransitiveDependencies() );
    }

    private ClazzpathUnit addDependencyToClasspath( Clazzpath cp, Artifact dependency )
        throws IOException
    {
        ClazzpathUnit clazzpathUnit = null;
        try ( InputStream is = new FileInputStream( dependency.getFile() ) )
        {
            clazzpathUnit = cp.addClazzpathUnit( is, dependency.toString() );
        }
        catch ( ZipException e )
        {
            log.warn( dependency.getFile()
                + " could not be unpacked/read for minimization; dependency is probably malformed." );
            IOException ioe = new IOException( "Dependency " + dependency.toString() + " in file "
                + dependency.getFile() + " could not be unpacked. File is probably corrupt", e );
            throw ioe;
        }
        catch ( ArrayIndexOutOfBoundsException | IllegalArgumentException e )
        {
            // trap ArrayIndexOutOfBoundsExceptions caused by malformed dependency classes (MSHADE-107)
            log.warn( dependency.toString()
                + " could not be analyzed for minimization; dependency is probably malformed." );
        }

        return clazzpathUnit;
    }

    private void removePackages( ClazzpathUnit artifactUnit )
    {
        Set<String> packageNames = new HashSet<>();
        removePackages( artifactUnit.getClazzes(), packageNames );
        removePackages( artifactUnit.getTransitiveDependencies(), packageNames );
    }

    @SuppressWarnings( "rawtypes" )
    private void removePackages( Set clazzes, Set<String> packageNames )
    {
        for ( Object clazze : clazzes )
        {
            Clazz clazz = (Clazz) clazze;
            String name = clazz.getName();
            while ( name.contains( "." ) )
            {
                name = name.substring( 0, name.lastIndexOf( '.' ) );
                if ( packageNames.add( name ) )
                {
                    removable.remove( new Clazz( name + ".package-info" ) );
                }
            }
        }
    }

    private void removeSpecificallyIncludedClasses( MavenProject project, List<SimpleFilter> simpleFilters )
        throws IOException
    {
        // remove classes specifically included in filters
        Clazzpath checkCp = new Clazzpath();
        for ( Artifact dependency : project.getArtifacts() )
        {
            File jar = dependency.getFile();

            for ( SimpleFilter simpleFilter : simpleFilters )
            {
                if ( simpleFilter.canFilter( jar ) )
                {
                    ClazzpathUnit depClazzpathUnit = addDependencyToClasspath( checkCp, dependency );
                    if ( depClazzpathUnit != null )
                    {
                        Set<Clazz> clazzes = depClazzpathUnit.getClazzes();
                        Iterator<Clazz> j = removable.iterator();
                        while ( j.hasNext() )
                        {
                            Clazz clazz = j.next();

                            if ( clazzes.contains( clazz ) //
                                && simpleFilter.isSpecificallyIncluded( clazz.getName().replace( '.', '/' ) ) )
                            {
                                log.info( clazz.getName() + " not removed because it was specifically included" );
                                j.remove();
                            }
                        }
                    }
                }
            }
        }
    }

    /** {@inheritDoc} */
    public boolean canFilter( File jar )
    {
        return true;
    }

    /** {@inheritDoc} */
    public boolean isFiltered( String classFile )
    {
        String className = classFile.replace( '/', '.' ).replaceFirst( "\\.class$", "" );
        Clazz clazz = new Clazz( className );

        if ( removable != null && removable.contains( clazz ) )
        {
            log.debug( "Removing " + className );
            classesRemoved += 1;
            return true;
        }

        classesKept += 1;
        return false;
    }

    /** {@inheritDoc} */
    public void finished()
    {
        int classesTotal = classesRemoved + classesKept;
        if ( classesTotal != 0 )
        {
            log.info( "Minimized " + classesTotal + " -> " + classesKept + " (" + 100 * classesKept / classesTotal
                + "%)" );
        }
        else
        {
            log.info( "Minimized " + classesTotal + " -> " + classesKept );
        }
    }
}
