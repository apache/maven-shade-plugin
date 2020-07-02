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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.plugins.shade.relocation.Relocator;

/**
 * A resource processor that allows the arbitrary addition of attributes to
 * the first MANIFEST.MF that is found in the set of JARs being processed, or
 * to a newly created manifest for the shaded JAR.
 *
 * @author Jason van Zyl
 * @since 1.2
 */
public class ManifestResourceTransformer
    extends AbstractCompatibilityTransformer
{
    private final List<String> defaultAttributes = Arrays.asList( "Export-Package",
                                                                  "Import-Package",
                                                                  "Provide-Capability",
                                                                  "Require-Capability" ); 
    
    // Configuration
    private String mainClass;

    private Map<String, Object> manifestEntries;

    private List<String> additionalAttributes;

    // Fields
    private boolean manifestDiscovered;

    private Manifest manifest;

    private long time = Long.MIN_VALUE;

    private String shade;

    public void setMainClass( String mainClass )
    {
        this.mainClass = mainClass;
    }
    
    public void setManifestEntries( Map<String, Object> manifestEntries )
    {
        this.manifestEntries = manifestEntries;
    }
    
    public void setAdditionalAttributes( List<String> additionalAttributes )
    {
        this.additionalAttributes = additionalAttributes;
    }

    @Override
    public boolean canTransformResource( String resource )
    {
        if ( JarFile.MANIFEST_NAME.equalsIgnoreCase( resource ) )
        {
            return true;
        }

        return false;
    }

    @Override
    public void processResource( String resource, InputStream is, List<Relocator> relocators, long time )
        throws IOException
    {
        // We just want to take the first manifest we come across as that's our project's manifest. This is the behavior
        // now which is situational at best. Right now there is no context passed in with the processing so we cannot
        // tell what artifact is being processed.
        if ( !manifestDiscovered )
        {
            manifest = new Manifest( is );

            if ( relocators != null && !relocators.isEmpty() ) 
            {
                final Attributes attributes = manifest.getMainAttributes();

                for ( final String attribute : defaultAttributes )
                {
                    final String attributeValue = attributes.getValue( attribute );
                    if ( attributeValue != null )
                    {
                        String newValue = relocate( attributeValue, relocators );
                        attributes.putValue( attribute, newValue );
                    }
                }

                if ( additionalAttributes != null )
                {
                    for ( final String attribute : additionalAttributes )
                    {
                        final String attributeValue = attributes.getValue( attribute );
                        if ( attributeValue != null )
                        {
                            String newValue = relocate( attributeValue, relocators );
                            attributes.putValue( attribute, newValue );
                        }
                    }
                }
            }

            manifestDiscovered = true;

            if ( time > this.time )
            {
                this.time = time;        
            }
        }
    }

    @Override
    public boolean hasTransformedResource()
    {
        return true;
    }

    @Override
    public void modifyOutputStream( JarOutputStream jos )
        throws IOException
    {
        // If we didn't find a manifest, then let's create one.
        if ( manifest == null )
        {
            manifest = new Manifest();
        }

        Attributes attributes = manifest.getMainAttributes();

        if ( mainClass != null )
        {
            attributes.put( Attributes.Name.MAIN_CLASS, mainClass );
        }

        if ( manifestEntries != null )
        {
            for ( Map.Entry<String, Object> entry : manifestEntries.entrySet() )
            {
                attributes.put( new Attributes.Name( entry.getKey() ), entry.getValue() );
            }
        }

        JarEntry jarEntry = new JarEntry( JarFile.MANIFEST_NAME );
        jarEntry.setTime( time );
        jos.putNextEntry( jarEntry );
        manifest.write( jos );
    }
    
    private String relocate( String originalValue, List<Relocator> relocators )
    {
        String newValue = originalValue;
        for ( Relocator relocator : relocators )
        {
            String value;
            do
            {
                value = newValue;
                newValue = relocator.relocateClass( value );
            }
            while ( !value.equals( newValue ) );
        }
        return newValue;
    }

    /**
     * The shades to apply this transformer to or no shades if no filter is applied.
     *
     * @param shade {@code null}, {@code jar}, {@code test-jar}, {@code sources-jar} or {@code test-sources-jar}.
     */
    public void setForShade( String shade )
    {
        this.shade = shade;
    }

    public boolean isForShade( String shade )
    {
        return this.shade == null || this.shade.isEmpty() || this.shade.equalsIgnoreCase( shade );
    }
}
