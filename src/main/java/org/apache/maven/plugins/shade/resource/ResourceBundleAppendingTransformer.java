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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.codehaus.plexus.util.IOUtil;

/**
 * An appending transformer for resource bundles
 *
 * @author Robert Scholte
 * @since 3.0.0
 */
public class ResourceBundleAppendingTransformer extends AbstractCompatibilityTransformer {
    private Map<String, ByteArrayOutputStream> dataMap = new HashMap<>();

    private Pattern resourceBundlePattern;

    private long time = Long.MIN_VALUE;

    /**
     * the base name of the resource bundle, a fully qualified class name
     * @param basename The basename.
     */
    public void setBasename(String basename) {
        resourceBundlePattern = Pattern.compile(basename + "(_[a-zA-Z]+){0,3}\\.properties");
    }

    @Override
    public boolean canTransformResource(String r) {
        return resourceBundlePattern != null && resourceBundlePattern.matcher(r).matches();
    }

    @Override
    public void processResource(String resource, InputStream is, List<Relocator> relocators, long time)
            throws IOException {
        ByteArrayOutputStream data = dataMap.get(resource);
        if (data == null) {
            data = new ByteArrayOutputStream();
            dataMap.put(resource, data);
        }

        IOUtil.copy(is, data);
        data.write('\n');

        if (time > this.time) {
            this.time = time;
        }
    }

    @Override
    public boolean hasTransformedResource() {
        return !dataMap.isEmpty();
    }

    @Override
    public void modifyOutputStream(JarOutputStream jos) throws IOException {
        for (Map.Entry<String, ByteArrayOutputStream> dataEntry : dataMap.entrySet()) {
            JarEntry jarEntry = new JarEntry(dataEntry.getKey());
            jarEntry.setTime(time);
            jos.putNextEntry(jarEntry);

            jos.write(dataEntry.getValue().toByteArray());
            dataEntry.getValue().reset();
        }
    }
}
