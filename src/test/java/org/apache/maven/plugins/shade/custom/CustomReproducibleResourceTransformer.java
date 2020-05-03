package org.apache.maven.plugins.shade.custom;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.List;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ReproducibleResourceTransformer;

/**
 * Custom ReproducibleResourceTransformer for MSHADE-363_old-plugin IT, to check that it can be run with
 * an older maven-shade-plugin that does not contain the ReproducibleResourceTransformer interface.
 */
public class CustomReproducibleResourceTransformer
    implements ReproducibleResourceTransformer
{
    @Override
    public boolean canTransformResource( final String resource )
    {
        return true;
    }

    /**
     * old non-reproducible RessourceTransformer API that will be used by maven-shade-plugin up to 3.2.2.
     */
    @Override
    public final void processResource( final String resource, final InputStream is, final List<Relocator> relocators )
        throws IOException
    {
        System.out.println( "Custom ResourceTransformer called through old API" );
        // call new ReproducibleRessourceTransformer API using a conventional timestamp
        processResource( resource, is, relocators, 0 );
    }

    /**
     * new reproducible API
     */
    @Override
    public void processResource( final String resource, final InputStream is, final List<Relocator> relocators,
                                 long time )
        throws IOException
    {
        System.out.println( "Custom ResourceTransformer called through new Reprodcible API" );
    }

    @Override
    public boolean hasTransformedResource()
    {
        return true;
    }

    @Override
    public void modifyOutputStream( JarOutputStream os )
        throws IOException
    {
        // do-op for this test
    }
}