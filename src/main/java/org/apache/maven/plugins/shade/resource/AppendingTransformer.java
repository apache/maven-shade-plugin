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

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.codehaus.plexus.util.IOUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * A resource processor that appends content for a resource, separated by a newline.
 */
public class AppendingTransformer
    implements ResourceTransformer
{
    String resource;

    ByteArrayOutputStream data = new ByteArrayOutputStream();

    public boolean canTransformResource( String r )
    {
        if ( resource != null )
        {
            if ( resource.equalsIgnoreCase( r ) )
            {
                return true;
            }

            if ( resource.endsWith( "*" ) && ( ( resource.length() - 1 ) <= r.length() ) )
            {
                String requiredPath = resource.substring( 0, resource.length() - 1 );
                String path = r.substring( 0, requiredPath.length() );

                return requiredPath.equalsIgnoreCase( path );
            }
        }

        return false;
    }

    public void processResource( String resource, InputStream is, List<Relocator> relocators )
        throws IOException
    {
        IOUtil.copy( is, data );
        data.write( '\n' );
    }

    public boolean hasTransformedResource()
    {
        return data.size() > 0;
    }

    public void modifyOutputStream( JarOutputStream jos )
        throws IOException
    {
        jos.putNextEntry( new JarEntry( resource ) );

        jos.write( data.toByteArray() );
        data.reset();
    }
}
