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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;

/**
 * A resource processor that aggregates plexus <code>components.xml</code> files.
 */
public class ComponentsXmlResourceTransformer extends AbstractCompatibilityTransformer {
    private Map<String, Xpp3Dom> components = new LinkedHashMap<>();

    private long time = Long.MIN_VALUE;

    public static final String COMPONENTS_XML_PATH = "META-INF/plexus/components.xml";

    @Override
    public boolean canTransformResource(String resource) {
        return COMPONENTS_XML_PATH.equals(resource);
    }

    @Override
    public void processResource(String resource, InputStream is, List<Relocator> relocators, long time)
            throws IOException {
        Xpp3Dom newDom;

        try {
            BufferedInputStream bis = new BufferedInputStream(is) {
                @Override
                public void close() throws IOException {
                    // leave ZIP open
                }
            };

            Reader reader = ReaderFactory.newXmlReader(bis);

            newDom = Xpp3DomBuilder.build(reader);
        } catch (Exception e) {
            throw new IOException("Error parsing components.xml in " + is, e);
        }

        // Only try to merge in components if there are some elements in the component-set
        if (newDom.getChild("components") == null) {
            return;
        }

        Xpp3Dom[] children = newDom.getChild("components").getChildren("component");

        for (Xpp3Dom component : children) {
            String role = getValue(component, "role");
            role = getRelocatedClass(role, relocators);
            setValue(component, "role", role);

            String roleHint = getValue(component, "role-hint");

            String impl = getValue(component, "implementation");
            impl = getRelocatedClass(impl, relocators);
            setValue(component, "implementation", impl);

            String key = role + ':' + roleHint;
            if (components.containsKey(key)) {
                // TODO: use the tools in Plexus to merge these properly. For now, I just need an all-or-nothing
                // configuration carry over

                Xpp3Dom dom = components.get(key);
                if (dom.getChild("configuration") != null) {
                    component.addChild(dom.getChild("configuration"));
                }
            }

            Xpp3Dom requirements = component.getChild("requirements");
            if (requirements != null && requirements.getChildCount() > 0) {
                for (int r = requirements.getChildCount() - 1; r >= 0; r--) {
                    Xpp3Dom requirement = requirements.getChild(r);

                    String requiredRole = getValue(requirement, "role");
                    requiredRole = getRelocatedClass(requiredRole, relocators);
                    setValue(requirement, "role", requiredRole);
                }
            }

            components.put(key, component);
        }

        if (time > this.time) {
            this.time = time;
        }
    }

    @Override
    public void modifyOutputStream(JarOutputStream jos) throws IOException {
        JarEntry jarEntry = new JarEntry(COMPONENTS_XML_PATH);
        jarEntry.setTime(time);

        byte[] data = getTransformedResource();

        jos.putNextEntry(jarEntry);

        jos.write(data);

        components.clear();
    }

    @Override
    public boolean hasTransformedResource() {
        return !components.isEmpty();
    }

    byte[] getTransformedResource() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 4);

        try (Writer writer = WriterFactory.newXmlWriter(baos)) {
            Xpp3Dom dom = new Xpp3Dom("component-set");

            Xpp3Dom componentDom = new Xpp3Dom("components");

            dom.addChild(componentDom);

            for (Xpp3Dom component : components.values()) {
                componentDom.addChild(component);
            }

            Xpp3DomWriter.write(writer, dom);
        }

        return baos.toByteArray();
    }

    private String getRelocatedClass(String className, List<Relocator> relocators) {
        if (className != null && className.length() > 0 && relocators != null) {
            for (Relocator relocator : relocators) {
                if (relocator.canRelocateClass(className)) {
                    return relocator.relocateClass(className);
                }
            }
        }

        return className;
    }

    private static String getValue(Xpp3Dom dom, String element) {
        Xpp3Dom child = dom.getChild(element);

        return (child != null && child.getValue() != null) ? child.getValue() : "";
    }

    private static void setValue(Xpp3Dom dom, String element, String value) {
        Xpp3Dom child = dom.getChild(element);

        if (child == null || value == null || value.length() <= 0) {
            return;
        }

        child.setValue(value);
    }
}
