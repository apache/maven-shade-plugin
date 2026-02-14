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
package org.apache.maven.plugins.shade.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.maven.plugins.shade.relocation.Relocator;

/**
 * Transform resource ensuring reproducible output: that requires to get the timestamp of
 * the initial resources to define in a reproducible way the timestamp of the transformed
 * resource.
 *
 * @author Hervé Boutemy
 * @since 3.2.4
 */
public interface ReproducibleResourceTransformer extends ResourceTransformer {
    /**
     * Transform an individual resource.
     *
     * @param resource the resource name
     * @param is an input stream for the resource, the implementation should *not* close this stream
     * @param relocators  a list of relocators
     * @param time the time of the resource to process
     * @throws IOException when the IO blows up
     */
    void processResource(String resource, InputStream is, List<Relocator> relocators, long time) throws IOException;
}
