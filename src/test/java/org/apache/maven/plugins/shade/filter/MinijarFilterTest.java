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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

public class MinijarFilterTest
{

    @Rule
    public TemporaryFolder tempFolder = TemporaryFolder.builder().assureDeletion().build();

    private File outputDirectory;
    private File emptyFile;
    private File jarFile;
    private Log log;
    private ArgumentCaptor<CharSequence> logCaptor;

    @Before
    public void init()
        throws IOException
    {
        this.outputDirectory = tempFolder.newFolder();
        this.emptyFile = tempFolder.newFile();
        this.jarFile = tempFolder.newFile();
        new JarOutputStream( new FileOutputStream( this.jarFile ) ).close();
        this.log = mock(Log.class);
        logCaptor = ArgumentCaptor.forClass(CharSequence.class);
    }

    /**
     * This test will fail on JDK 7 because jdependency needs at least JDK 8.
     */
    @Test
    public void testWithMockProject()
        throws IOException
    {
        assumeFalse( "Expected to run under JDK8+", System.getProperty("java.version").startsWith("1.7") );

        MavenProject mavenProject = mockProject( outputDirectory, emptyFile );

        MinijarFilter mf = new MinijarFilter( mavenProject, log );

        mf.finished();

        verify( log, times( 1 ) ).info( logCaptor.capture() );

        assertEquals( "Minimized 0 -> 0", logCaptor.getValue() );

    }

    @Test
    public void testWithPomProject()
        throws IOException
    {
        // project with pom packaging and no artifact.
        MavenProject mavenProject = mockProject( outputDirectory, null );
        mavenProject.setPackaging( "pom" );

        MinijarFilter mf = new MinijarFilter( mavenProject, log );

        mf.finished();

        verify( log, times( 1 ) ).info( logCaptor.capture() );

        // verify no access to project's artifacts
        verify( mavenProject, times( 0 ) ).getArtifacts();

        assertEquals( "Minimized 0 -> 0", logCaptor.getValue() );

    }

    private MavenProject mockProject( File outputDirectory, File file, String... classPathElements )
    {
        MavenProject mavenProject = mock( MavenProject.class );

        Artifact artifact = mock( Artifact.class );
        when( artifact.getGroupId() ).thenReturn( "com" );
        when( artifact.getArtifactId() ).thenReturn( "aid" );
        when( artifact.getVersion() ).thenReturn( "1.9" );
        when( artifact.getClassifier() ).thenReturn( "classifier1" );
        when( artifact.getScope() ).thenReturn( Artifact.SCOPE_COMPILE );

        when( mavenProject.getArtifact() ).thenReturn( artifact );

        DefaultArtifact dependencyArtifact =
            new DefaultArtifact( "dep.com", "dep.aid", "1.0", "compile", "jar", "classifier2", null );
        dependencyArtifact.setFile( file );

        Set<Artifact> artifacts = new TreeSet<>();
        artifacts.add( dependencyArtifact );

        when( mavenProject.getArtifacts() ).thenReturn( artifacts );

        when( mavenProject.getArtifact().getFile() ).thenReturn( file );

        Build build = new Build();
        build.setOutputDirectory( outputDirectory.toString() );

        List<String> classpath = new ArrayList<>();
        classpath.add( outputDirectory.toString() );
        if ( file != null )
        {
            classpath.add(file.toString());
        }
        classpath.addAll( Arrays.asList( classPathElements ) );
        when( mavenProject.getBuild() ).thenReturn( build );
        try {
            when(mavenProject.getRuntimeClasspathElements()).thenReturn(classpath);
        } catch (DependencyResolutionRequiredException e) {
            fail("Encountered unexpected exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return mavenProject;
    }

    @Test
    public void finsishedShouldProduceMessageForClassesTotalNonZero()
    {
        MinijarFilter m = new MinijarFilter( 1, 50, log );

        m.finished();

        verify( log, times( 1 ) ).info( logCaptor.capture() );

        assertEquals( "Minimized 51 -> 1 (1%)", logCaptor.getValue() );

    }

    @Test
    public void finishedShouldProduceMessageForClassesTotalZero()
    {
        MinijarFilter m = new MinijarFilter( 0, 0, log );

        m.finished();

        verify( log, times( 1 ) ).info( logCaptor.capture() );

        assertEquals( "Minimized 0 -> 0", logCaptor.getValue() );

    }

    /**
     * Verify that directories are ignored when scanning the classpath for JARs containing services,
     * but warnings are logged instead
     *
     * @see <a href="https://issues.apache.org/jira/browse/MSHADE-366">MSHADE-366</a>
     */
    @Test
    public void removeServicesShouldIgnoreDirectories() throws Exception {
        String classPathElementToIgnore = tempFolder.newFolder().getAbsolutePath();
        MavenProject mockedProject = mockProject( outputDirectory, jarFile, classPathElementToIgnore );

        new MinijarFilter(mockedProject, log);

        verify( log, times( 1 ) ).warn( logCaptor.capture() );

        assertThat( logCaptor.getValue().toString(), startsWith(
                "Not a JAR file candidate. Ignoring classpath element '" + classPathElementToIgnore + "' (" ) );
    }

}
