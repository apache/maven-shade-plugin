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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Copy of original interface necessary to MSHADE-363_old-plugin IT:
 * CustomReproducibleResourceTransformer is built with ReproducibleResourceTransformer interface provided by
 * recent maven-shade-plugin, but older maven-shade-plugin 3.2.2 will be used at runtime in the
 * MSHADE-363_old-plugin IT, an older that does not provide the interface. Without the interface copy
 * in the custom resource transformer code, this would lead to ClassNotFoundException...
 * 
 * @since 3.2.4
 * @see org.apache.maven.plugins.shade.custom.CustomReproducibleResourceTransformer
 */
public interface ReproducibleResourceTransformer
    extends ResourceTransformer
{
    void processResource( String resource, InputStream is, List<Relocator> relocators, long time )
        throws IOException;
}
