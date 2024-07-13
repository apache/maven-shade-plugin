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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.codehaus.plexus.util.IOUtil;

/**
 * A resource processor that appends content for a resource, separated by a newline.
 */
public class AppendingTransformer extends AbstractCompatibilityTransformer {
    String resource;

    ByteArrayOutputStream data = new ByteArrayOutputStream();

    private long time = Long.MIN_VALUE;

    @Override
    public boolean canTransformResource(String r) {
        return resource != null && resource.equalsIgnoreCase(r);
    }

    @Override
    public void processResource(String resource, InputStream is, List<Relocator> relocators, long time)
            throws IOException {
        IOUtil.copy(is, data);
        data.write('\n');
        if (time > this.time) {
            this.time = time;
        }
    }

    @Override
    public boolean hasTransformedResource() {
        return data.size() > 0;
    }

    @Override
    public void modifyOutputStream(JarOutputStream jos) throws IOException {
        JarEntry jarEntry = new JarEntry(resource);
        jarEntry.setTime(time);
        jos.putNextEntry(jarEntry);

        jos.write(data.toByteArray());
        data.reset();
    }
}
