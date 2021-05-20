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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugins.shade.relocation.Relocator;

/**
 * Resource transformer that relocates classes in {@code META-INF/sisu/javax.inject.Named} and appends resources
 * into a single resource.
 *
 * @since 3.3.0
 */
public class SisuIndexResourceTransformer
    extends AbstractCompatibilityTransformer
{
    private static final String SISU_INDEX_PATH = "META-INF/sisu/javax.inject.Named";

    private final ArrayList<String> indexEntries = new ArrayList<>();

    private long time = Long.MIN_VALUE;

    @Override
    public boolean canTransformResource( final String resource )
    {
        return resource.equals( SISU_INDEX_PATH );
    }

    @Override
    public void processResource( final String resource,
                                 final InputStream is,
                                 final List<Relocator> relocators,
                                 long time ) throws IOException
    {
        Scanner scanner = new Scanner( is, StandardCharsets.UTF_8.name() );
        while ( scanner.hasNextLine() )
        {
            String relContent = scanner.nextLine();
            for ( Relocator relocator : relocators )
            {
                if ( relocator.canRelocateClass( relContent ) )
                {
                    relContent = relocator.applyToSourceContent( relContent );
                }
            }
            indexEntries.add( relContent );
        }

        if ( time > this.time )
        {
            this.time = time;        
        }
    }

    @Override
    public boolean hasTransformedResource()
    {
        return !indexEntries.isEmpty();
    }

    @Override
    public void modifyOutputStream( final JarOutputStream jos )
        throws IOException
    {
        JarEntry jarEntry = new JarEntry( SISU_INDEX_PATH );
        jarEntry.setTime( time );
        jos.putNextEntry( jarEntry );
        IOUtils.writeLines( indexEntries, "\n", jos, StandardCharsets.UTF_8 );
        jos.flush();
        indexEntries.clear();
   }
}
