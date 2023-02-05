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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Mojo that performs cleaning of the generated dependency-reduced-pom.xml files.
 *
 * @author Niels Basjes
 */
@Mojo( name = "clean", defaultPhase = LifecyclePhase.CLEAN )
public class CleanMojo
    extends AbstractMojo
{
    /**
     * The current Maven project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * Flag whether to generate a simplified POM for the shaded artifact. If set to <code>true</code>, dependencies that
     * have been included into the uber JAR will be removed from the <code>&lt;dependencies&gt;</code> section of the
     * generated POM. The reduced POM will be named <code>dependency-reduced-pom.xml</code> and is stored into the same
     * directory as the shaded artifact. Unless you also specify dependencyReducedPomLocation, the plugin will create a
     * temporary file named <code>dependency-reduced-pom.xml</code> in the project basedir.
     */
    @Parameter( defaultValue = "true" )
    private boolean createDependencyReducedPom;

    /**
     * Where to put the dependency reduced pom. Note: setting a value for this parameter with a directory other than
     * ${basedir} will change the value of ${basedir} for all executions that come after the shade execution. This is
     * often not what you want. This is considered an open issue with this plugin.
     *
     * @since 1.7
     */
    @Parameter( defaultValue = "${basedir}/dependency-reduced-pom.xml" )
    private File dependencyReducedPomLocation;

    /**
     * Create a dependency-reduced POM in ${basedir}/drp-UNIQUE.pom. This avoids build collisions of parallel builds
     * without moving the dependency-reduced POM to a different directory. The property
     * maven.shade.dependency-reduced-pom is set to the generated filename.
     *
     * @since 1.7.2
     */
    @Parameter( defaultValue = "false" )
    private boolean generateUniqueDependencyReducedPom;

    /**
     * When true, skips the execution of this MOJO.
     * @since 3.3.0
     */
    @Parameter( defaultValue = "false" )
    private boolean skip;

    private static final Pattern DRP_FILENAME_PATTERN = Pattern.compile( "dependency-reduced-pom(?:-\\w+)?\\.xml" );

    /**
     * @throws MojoExecutionException in case of an error.
     */
    public void execute()
        throws MojoExecutionException
    {
        if ( skip )
        {
            getLog().debug( "Cleaning has been skipped because 'skip' was set." );
            return;
        }

        // Only try to clean if generated
        if ( createDependencyReducedPom )
        {
            if ( project.getBasedir() == null )
            {
                throw new MojoExecutionException( "The project.basedir is null" );
            }

            // Remove the configured file name
            removeFile( dependencyReducedPomLocation );

            // Remove all default naming files from the default directory
            File[] listFiles = project.getBasedir().listFiles();
            if ( listFiles != null )
            {
                for ( File f : listFiles )
                {
                    if ( !f.isFile() )
                    {
                        continue;
                    }

                    if ( DRP_FILENAME_PATTERN.matcher( f.getName() ).find() )
                    {
                        removeFile( f );
                    }
                }
            }
        }
    }

    private void removeFile( File file )
    {
        if ( file.delete() )
        {
            getLog().info( "Deleting " + file.getAbsoluteFile() );
        }
        else
        {
            getLog().error( "Unable to delete " + file.getAbsoluteFile() );
        }
    }
}
