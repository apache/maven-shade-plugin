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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.codehaus.plexus.util.IOUtil;

/**
 * Trivial transformer applying relocators on resources content.
 */
public class RelocationTransformer extends BaseRelocatingTransformer
{
    private Collection<ResourceTransformer> delegates;
    private boolean transformed;

    @Override
    public boolean canTransformResource( String resource )
    {
        if ( delegates == null )
        {
            return false;
        }
        for ( ResourceTransformer transformer : delegates )
        {
            if ( transformer.canTransformResource( resource ) )
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public void processResource( String resource, InputStream is, List<Relocator> relocators ) throws IOException
    {
        byte[] relocated = null;
        for ( ResourceTransformer transformer : delegates )
        {
            if ( transformer.canTransformResource( resource ) )
            {
                transformed = true;
                if ( relocated == null )
                {
                    relocated = relocate( IOUtil.toString( is ), relocators )
                            .getBytes( StandardCharsets.UTF_8 );
                }
                transformer.processResource(
                        resource,
                        new ByteArrayInputStream( relocated ),
                        relocators );
            }
        }
    }

    @Override
    public boolean hasTransformedResource()
    {
        return transformed;
    }

    @Override
    public void modifyOutputStream( JarOutputStream os ) throws IOException
    {
        if ( !transformed )
        {
            return;
        }
        for ( ResourceTransformer transformer : delegates )
        {
            if ( transformer.hasTransformedResource() )
            {
                transformer.modifyOutputStream( os );
            }
        }
    }

    public void setDelegates( Collection<ResourceTransformer> delegates )
    {
        this.delegates = delegates;
    }
}
