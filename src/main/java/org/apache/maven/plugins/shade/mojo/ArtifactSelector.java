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

import org.apache.maven.artifact.Artifact;

import java.util.Collection;
import java.util.HashSet;

/**
 * @author Benjamin Bentmann
 */
class ArtifactSelector
{

    private Collection<ArtifactId> includes;

    private Collection<ArtifactId> excludes;

    ArtifactSelector( Artifact projectArtifact, ArtifactSet artifactSet, String groupPrefix )
    {
        this( ( artifactSet != null ) ? artifactSet.getIncludes() : null,
              ( artifactSet != null ) ? artifactSet.getExcludes() : null, groupPrefix );

        if ( projectArtifact != null && !this.includes.isEmpty() )
        {
            this.includes.add( new ArtifactId( projectArtifact ) );
        }
    }

    ArtifactSelector( Collection<String> includes, Collection<String> excludes, String groupPrefix )
    {
        this.includes = toIds( includes );
        this.excludes = toIds( excludes );

        if ( groupPrefix != null && groupPrefix.length() > 0 )
        {
            this.includes.add( new ArtifactId( groupPrefix + "*", "*", "*", "*" ) );
        }
    }

    private static Collection<ArtifactId> toIds( Collection<String> patterns )
    {
        Collection<ArtifactId> result = new HashSet<>();

        if ( patterns != null )
        {
            for ( String pattern : patterns )
            {
                result.add( new ArtifactId( pattern ) );
            }
        }

        return result;
    }

    public boolean isSelected( Artifact artifact )
    {
        return ( artifact != null ) ? isSelected( new ArtifactId( artifact ) ) : false;
    }

    boolean isSelected( ArtifactId id )
    {
        return ( includes.isEmpty() || matches( includes, id ) ) && !matches( excludes, id );
    }

    private boolean matches( Collection<ArtifactId> patterns, ArtifactId id )
    {
        for ( ArtifactId pattern : patterns )
        {
            if ( id.matches( pattern ) )
            {
                return true;
            }
        }
        return false;
    }

}
