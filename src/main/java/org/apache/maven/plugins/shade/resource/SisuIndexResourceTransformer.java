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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugins.shade.relocation.Relocator;

/**
 * Resources transformer that relocates classes in {@code META-INF/sisu/javax.inject.Named} and appends resources
 * into a single resource.
 *
 * @since 3.3.0
 */
public class SisuIndexResourceTransformer
    extends AbstractCompatibilityTransformer
{
    private static final String SISU_INDEX_PATH = "META-INF/sisu/javax.inject.Named";

    private static final String NEWLINE = "\n";

    private ServiceStream serviceStream;

    private List<Relocator> relocators;

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
        if ( serviceStream == null )
        {
            serviceStream = new ServiceStream();
        }

        final String content = IOUtils.toString( is, StandardCharsets.UTF_8 );
        StringReader reader = new StringReader( content );
        BufferedReader lineReader = new BufferedReader( reader );
        String line;
        while ( ( line = lineReader.readLine() ) != null )
        {
            String relContent = line;
            for ( Relocator relocator : relocators )
            {
                if ( relocator.canRelocateClass( relContent ) )
                {
                    relContent = relocator.applyToSourceContent( relContent );
                }
            }
            serviceStream.append( relContent + NEWLINE );
        }

        if ( this.relocators == null )
        {
            this.relocators = relocators;
        }

        if ( time > this.time )
        {
            this.time = time;        
        }
    }

    @Override
    public boolean hasTransformedResource()
    {
        return serviceStream != null;
    }

    @Override
    public void modifyOutputStream( final JarOutputStream jos )
        throws IOException
    {
        JarEntry jarEntry = new JarEntry( SISU_INDEX_PATH );
        jarEntry.setTime( time );
        jos.putNextEntry( jarEntry );
        IOUtils.copy( serviceStream.toInputStream(), jos );
        jos.flush();
        serviceStream.reset();
   }

    static class ServiceStream
        extends ByteArrayOutputStream
    {

        ServiceStream()
        {
            super( 1024 );
        }

        public void append( String content )
            throws IOException
        {
            byte[] contentBytes = content.getBytes( StandardCharsets.UTF_8 );
            this.write( contentBytes );
            this.write( NEWLINE.getBytes( StandardCharsets.UTF_8 ) );
        }

        public InputStream toInputStream()
        {
            return new ByteArrayInputStream( buf, 0, count );
        }
    }
}
