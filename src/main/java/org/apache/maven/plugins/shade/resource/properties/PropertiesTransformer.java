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
package org.apache.maven.plugins.shade.resource.properties;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ReproducibleResourceTransformer;
import org.apache.maven.plugins.shade.resource.properties.io.NoCloseOutputStream;
import org.apache.maven.plugins.shade.resource.properties.io.SkipPropertiesDateLineWriter;

/**
 * Enables to merge a set of properties respecting priority between them.
 *
 * @since 3.2.2
 */
public class PropertiesTransformer implements ReproducibleResourceTransformer {
    private String resource;
    private String alreadyMergedKey;
    private String ordinalKey;
    private int defaultOrdinal;
    private boolean reverseOrder;
    private long time = Long.MIN_VALUE;

    private final List<Properties> properties = new ArrayList<>();

    public PropertiesTransformer() {
        // no-op
    }

    protected PropertiesTransformer(
            final String resource, final String ordinalKey, final int defaultOrdinal, final boolean reversed) {
        this.resource = resource;
        this.ordinalKey = ordinalKey;
        this.defaultOrdinal = defaultOrdinal;
        this.reverseOrder = reversed;
    }

    @Override
    public boolean canTransformResource(final String resource) {
        return Objects.equals(resource, this.resource);
    }

    @Override
    public final void processResource(String resource, InputStream is, List<Relocator> relocators) throws IOException {
        processResource(resource, is, relocators, 0);
    }

    @Override
    public void processResource(
            final String resource, final InputStream is, final List<Relocator> relocators, long time)
            throws IOException {
        final Properties p = new Properties();
        p.load(is);
        properties.add(p);
        if (time > this.time) {
            this.time = time;
        }
    }

    @Override
    public boolean hasTransformedResource() {
        return !properties.isEmpty();
    }

    @Override
    public void modifyOutputStream(JarOutputStream os) throws IOException {
        if (properties.isEmpty()) {
            return;
        }

        final Properties out = mergeProperties(sortProperties());
        if (ordinalKey != null) {
            out.remove(ordinalKey);
        }
        if (alreadyMergedKey != null) {
            out.remove(alreadyMergedKey);
        }
        JarEntry jarEntry = new JarEntry(resource);
        jarEntry.setTime(time);
        os.putNextEntry(jarEntry);
        final BufferedWriter writer = new SkipPropertiesDateLineWriter(
                new OutputStreamWriter(new NoCloseOutputStream(os), StandardCharsets.ISO_8859_1));
        out.store(writer, " Merged by maven-shade-plugin (" + getClass().getName() + ")");
        writer.close();
        os.closeEntry();
    }

    public void setReverseOrder(final boolean reverseOrder) {
        this.reverseOrder = reverseOrder;
    }

    public void setResource(final String resource) {
        this.resource = resource;
    }

    public void setOrdinalKey(final String ordinalKey) {
        this.ordinalKey = ordinalKey;
    }

    public void setDefaultOrdinal(final int defaultOrdinal) {
        this.defaultOrdinal = defaultOrdinal;
    }

    public void setAlreadyMergedKey(final String alreadyMergedKey) {
        this.alreadyMergedKey = alreadyMergedKey;
    }

    private List<Properties> sortProperties() {
        final List<Properties> sortedProperties = new ArrayList<>();
        boolean foundMaster = false;
        for (final Properties current : properties) {
            if (alreadyMergedKey != null) {
                final String master = current.getProperty(alreadyMergedKey);
                if (Boolean.parseBoolean(master)) {
                    if (foundMaster) {
                        throw new IllegalStateException(
                                "Ambiguous merged values: " + sortedProperties + ", " + current);
                    }
                    foundMaster = true;
                    sortedProperties.clear();
                    sortedProperties.add(current);
                }
            }
            if (!foundMaster) {
                final int configOrder = getConfigurationOrdinal(current);

                int i;
                for (i = 0; i < sortedProperties.size(); i++) {
                    int listConfigOrder = getConfigurationOrdinal(sortedProperties.get(i));
                    if ((!reverseOrder && listConfigOrder > configOrder)
                            || (reverseOrder && listConfigOrder < configOrder)) {
                        break;
                    }
                }
                sortedProperties.add(i, current);
            }
        }
        return sortedProperties;
    }

    private int getConfigurationOrdinal(final Properties p) {
        if (ordinalKey == null) {
            return defaultOrdinal;
        }
        final String configOrderString = p.getProperty(ordinalKey);
        if (configOrderString != null && configOrderString.length() > 0) {
            return Integer.parseInt(configOrderString);
        }
        return defaultOrdinal;
    }

    private static Properties mergeProperties(final List<Properties> sortedProperties) {
        final Properties mergedProperties = new SortedProperties();
        for (final Properties p : sortedProperties) {
            mergedProperties.putAll(p);
        }
        return mergedProperties;
    }
}
