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
package org.apache.maven.plugins.shade;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;

/**
 * Parameter object used to pass multitude of args to Shader.shade()
 *
 * @since 2.0
 */
public class ShadeRequest {

    private Set<File> jars;

    private File uberJar;

    private List<Filter> filters;

    private List<Relocator> relocators;

    private List<ResourceTransformer> resourceTransformers;

    private boolean shadeSourcesContent;

    public Set<File> getJars() {
        return jars;
    }

    /**
     * Which jars to shade.
     *
     * @param jars the jars
     */
    public void setJars(Set<File> jars) {
        this.jars = jars;
    }

    public File getUberJar() {
        return uberJar;
    }

    /**
     * Output jar.
     *
     * @param uberJar the ueberJar file
     */
    public void setUberJar(File uberJar) {
        this.uberJar = uberJar;
    }

    public List<Filter> getFilters() {
        return filters;
    }

    /**
     * The filters.
     *
     * @param filters the filters
     */
    public void setFilters(List<Filter> filters) {
        this.filters = filters;
    }

    public List<Relocator> getRelocators() {
        return relocators;
    }

    /**
     * The relocators.
     *
     * @param relocators the relocators
     */
    public void setRelocators(List<Relocator> relocators) {
        this.relocators = relocators;
    }

    public List<ResourceTransformer> getResourceTransformers() {
        return resourceTransformers;
    }

    /**
     * The transformers.
     *
     * @param resourceTransformers list of resourceTransformers
     */
    public void setResourceTransformers(List<ResourceTransformer> resourceTransformers) {
        this.resourceTransformers = resourceTransformers;
    }

    public boolean isShadeSourcesContent() {
        return shadeSourcesContent;
    }

    /**
     * When true, it will attempt to shade the contents of the java source files when creating the sources jar.
     * When false, it will just relocate the java source files to the shaded paths, but will not modify the
     * actual contents of the java source files.
     *
     * @param shadeSourcesContent {@code true} or {@code false}
     */
    public void setShadeSourcesContent(boolean shadeSourcesContent) {
        this.shadeSourcesContent = shadeSourcesContent;
    }
}
