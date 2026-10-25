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

    ByteArrayOutputStream data = new LastByteByteArrayOutputStream();

    private boolean resourceProcessed;

    private long time = Long.MIN_VALUE;

    @Override
    public boolean canTransformResource(String r) {
        return resource != null && resource.equalsIgnoreCase(r);
    }

    @Override
    public void processResource(String resource, InputStream is, List<Relocator> relocators, long time)
            throws IOException {
        int previousSize = data.size();
        IOUtil.copy(is, data);
        if (data.size() > previousSize && lastByte() != '\n') {
            data.write('\n');
        }
        resourceProcessed = true;
        if (time > this.time) {
            this.time = time;
        }
    }

    @Override
    public boolean hasTransformedResource() {
        return resourceProcessed;
    }

    @Override
    public void modifyOutputStream(JarOutputStream jos) throws IOException {
        JarEntry jarEntry = new JarEntry(resource);
        jarEntry.setTime(time);
        jos.putNextEntry(jarEntry);

        data.writeTo(jos);
        data.reset();
        resourceProcessed = false;
    }

    private int lastByte() {
        return ((LastByteByteArrayOutputStream) data).lastByte();
    }

    private static class LastByteByteArrayOutputStream extends ByteArrayOutputStream {

        synchronized int lastByte() {
            return count == 0 ? -1 : buf[count - 1] & 0xff;
        }
    }
}
