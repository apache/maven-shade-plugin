package org.apache.maven.plugins.shade.mojo;

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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.codehaus.plexus.PlexusTestCase;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class ShadeMojoTest
    extends PlexusTestCase
{
    public void testShaderWithDefaultShadedPattern()
        throws Exception
    {
        shaderWithPattern( null, new File( "target/foo-default.jar" ) );
    }

    public void testShaderWithCustomShadedPattern()
        throws Exception
    {
        shaderWithPattern( "org/shaded/plexus/util", new File( "target/foo-custom.jar" ) );
    }

    public void testShaderWithExclusions()
        throws Exception
    {
        File jarFile = new File( getBasedir(), "target/unit/foo-bar.jar" );

        Shader s = (Shader) lookup( Shader.ROLE, "default" );

        Set<File> set = new LinkedHashSet<>();
        set.add( new File( getBasedir(), "src/test/jars/test-artifact-1.0-SNAPSHOT.jar" ) );

        List<Relocator> relocators = new ArrayList<>();
        relocators.add( new SimpleRelocator( "org.codehaus.plexus.util", "hidden", null, Arrays.asList(
                "org.codehaus.plexus.util.xml.Xpp3Dom", "org.codehaus.plexus.util.xml.pull.*") ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( jarFile );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );

        s.shade( shadeRequest );

        try ( URLClassLoader cl = new URLClassLoader( new URL[]{ jarFile.toURI().toURL() } ) ) {
            Class<?> c = cl.loadClass( "org.apache.maven.plugins.shade.Lib" );
    
            Field field = c.getDeclaredField( "CLASS_REALM_PACKAGE_IMPORT" );
            assertEquals( "org.codehaus.plexus.util.xml.pull", field.get( null ) );
    
            Method method = c.getDeclaredMethod( "getClassRealmPackageImport" );
            assertEquals( "org.codehaus.plexus.util.xml.pull", method.invoke( null ) );
        }
    }

    /**
     * Tests if a Filter is installed correctly, also if createSourcesJar is set to true.
     *
     * @throws Exception
     */
    public void testShadeWithFilter()
        throws Exception
    {
        ShadeMojo mojo = new ShadeMojo();

        // set createSourcesJar = true
        Field createSourcesJar = ShadeMojo.class.getDeclaredField( "createSourcesJar" );
        createSourcesJar.setAccessible( true );
        createSourcesJar.set( mojo, Boolean.TRUE );

        // configure artifactResolver (mocked) for mojo
        ArtifactResolver mockArtifactResolver = new ArtifactResolver()
        {
            @Override
            public ArtifactResult resolveArtifact( ProjectBuildingRequest req, final Artifact art )
                throws ArtifactResolverException
            {
                return new ArtifactResult()
                {

                    @Override
                    public Artifact getArtifact()
                    {
                        art.setResolved( true );
                        String fileName = art.getArtifactId() + "-" + art.getVersion()
                            + ( art.getClassifier() != null ? "-" + art.getClassifier() : "" ) + ".jar";
                        art.setFile( new File( fileName ) );
                        return art;
                    }
                };
            }

            @Override
            public ArtifactResult resolveArtifact( ProjectBuildingRequest req, final ArtifactCoordinate coordinate )
                throws ArtifactResolverException
            {
                return new ArtifactResult()
                {

                    @Override
                    public Artifact getArtifact()
                    {
                        Artifact art = mock( Artifact.class );
                        when( art.getGroupId() ).thenReturn( coordinate.getGroupId() );
                        when( art.getArtifactId() ).thenReturn( coordinate.getArtifactId() );
                        when( art.getType() ).thenReturn( coordinate.getExtension() );
                        when( art.getClassifier() ).thenReturn( coordinate.getClassifier() );
                        when( art.isResolved() ).thenReturn( true );
                        String fileName = coordinate.getArtifactId() + "-" + coordinate.getVersion()
                            + ( coordinate.getClassifier() != null ? "-" + coordinate.getClassifier() : "" ) + ".jar";
                        when( art.getFile() ).thenReturn( new File( fileName ) );
                        return art;
                    }
                };
            }
        };
        Field artifactResolverField = ShadeMojo.class.getDeclaredField( "artifactResolver" );
        artifactResolverField.setAccessible( true );
        artifactResolverField.set( mojo, mockArtifactResolver );

        // create and configure MavenProject
        MavenProject project = new MavenProject();
        ArtifactHandler artifactHandler = (ArtifactHandler) lookup( ArtifactHandler.ROLE );
        Artifact artifact = new DefaultArtifact( "org.apache.myfaces.core", "myfaces-impl",
                                                 VersionRange.createFromVersion( "2.0.1-SNAPSHOT" ), "compile", "jar",
                                                 null, artifactHandler );
        artifact = mockArtifactResolver.resolveArtifact( null, artifact ).getArtifact(); // setFile and setResolved
        project.setArtifact( artifact );
        Field projectField = ShadeMojo.class.getDeclaredField( "project" );
        projectField.setAccessible( true );
        projectField.set( mojo, project );

        // create and configure the ArchiveFilter
        ArchiveFilter archiveFilter = new ArchiveFilter();
        Field archiveFilterArtifact = ArchiveFilter.class.getDeclaredField( "artifact" );
        archiveFilterArtifact.setAccessible( true );
        archiveFilterArtifact.set( archiveFilter, "org.apache.myfaces.core:myfaces-impl" );

        // add ArchiveFilter to mojo
        Field filtersField = ShadeMojo.class.getDeclaredField( "filters" );
        filtersField.setAccessible( true );
        filtersField.set( mojo, new ArchiveFilter[]{ archiveFilter } );

        Field sessionField = ShadeMojo.class.getDeclaredField( "session" );
        sessionField.setAccessible( true );
        sessionField.set( mojo, mock( MavenSession.class ) );

        // invoke getFilters()
        Method getFilters = ShadeMojo.class.getDeclaredMethod( "getFilters" );
        getFilters.setAccessible( true );
        List<Filter> filters = (List<Filter>) getFilters.invoke( mojo);

        // assertions - there must be one filter
        assertEquals( 1, filters.size() );

        // the filter must be able to filter the binary and the sources jar
        Filter filter = filters.get( 0 );
        assertTrue( filter.canFilter( new File( "myfaces-impl-2.0.1-SNAPSHOT.jar" ) ) ); // binary jar
        assertTrue( filter.canFilter( new File( "myfaces-impl-2.0.1-SNAPSHOT-sources.jar" ) ) ); // sources jar
    }

    public void shaderWithPattern( String shadedPattern, File jar )
        throws Exception
    {
        Shader s = (Shader) lookup( Shader.ROLE );

        Set<File> set = new LinkedHashSet<>();

        set.add( new File( getBasedir(), "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( getBasedir(), "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util", shadedPattern, null, Arrays.asList(
                "org/codehaus/plexus/util/xml/Xpp3Dom", "org/codehaus/plexus/util/xml/pull.*") ) );

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

}
