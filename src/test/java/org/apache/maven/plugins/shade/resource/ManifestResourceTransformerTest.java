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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ManifestResourceTransformerTest {
    private ManifestResourceTransformer transformer;

    @Before
    public void setUp() {
        transformer = new ManifestResourceTransformer();
    }

    @Test
    public void rewriteDefaultAttributes() throws Exception {
        final Manifest manifest = new Manifest();
        final Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(
                "Export-Package",
                "javax.decorator;version=\"2.0\";uses:=\"javax.enterprise.inject\","
                        + "javax.enterprise.context;version=\"2.0\";uses:=\"javax.enterprise.util,javax.inject\"");
        attributes.putValue("Import-Package", "javax.el,javax.enterprise.context;version=\"[2.0,3)\"");
        attributes.putValue(
                "Provide-Capability",
                "osgi.contract;osgi.contract=JavaCDI;uses:=\""
                        + "javax.enterprise.context,javax.enterprise.context.spi,javax.enterprise.context.control,"
                        + "javax.enterprise.util,javax.enterprise.inject,javax.enterprise.inject.spi,"
                        + "javax.enterprise.inject.spi.configurator,javax.enterprise.inject.literal,"
                        + "javax.enterprise.inject.se,javax.enterprise.event,javax.decorator\";"
                        + "version:List<Version>=\"2.0,1.2,1.1,1.0\"");
        attributes.putValue(
                "Require-Capability",
                "osgi.serviceloader;"
                        + "filter:=\"(osgi.serviceloader=javax.enterprise.inject.se.SeContainerInitializer)\";"
                        + "cardinality:=multiple,"
                        + "osgi.serviceloader;"
                        + "filter:=\"(osgi.serviceloader=javax.enterprise.inject.spi.CDIProvider)\";"
                        + "cardinality:=multiple,osgi.extender;"
                        + "filter:=\"(osgi.extender=osgi.serviceloader.processor)\","
                        + "osgi.contract;osgi.contract=JavaEL;filter:=\"(&(osgi.contract=JavaEL)(version=2.2.0))\","
                        + "osgi.contract;osgi.contract=JavaInterceptor;"
                        + "filter:=\"(&(osgi.contract=JavaInterceptor)(version=1.2.0))\","
                        + "osgi.contract;osgi.contract=JavaInject;"
                        + "filter:=\"(&(osgi.contract=JavaInject)(version=1.0.0))\","
                        + "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version=1.8))\"");

        List<Relocator> relocators = Collections.<Relocator>singletonList(new SimpleRelocator(
                "javax", "jakarta", Collections.<String>emptyList(), Collections.<String>emptyList()));

        final ByteArrayOutputStream out = transform(manifest, relocators);

        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            Attributes attrs = jis.getManifest().getMainAttributes();
            assertEquals(
                    "jakarta.decorator;version=\"2.0\";uses:=\"jakarta.enterprise.inject\","
                            + "jakarta.enterprise.context;version=\"2.0\";uses:=\"jakarta.enterprise.util,"
                            + "jakarta.inject\"",
                    attrs.getValue("Export-Package"));
            assertEquals("jakarta.el,jakarta.enterprise.context;version=\"[2.0,3)\"", attrs.getValue("Import-Package"));
            assertEquals(
                    "osgi.contract;osgi.contract=JavaCDI;" + "uses:=\"jakarta.enterprise.context,"
                            + "jakarta.enterprise.context.spi,jakarta.enterprise.context.control,"
                            + "jakarta.enterprise.util,jakarta.enterprise.inject,jakarta.enterprise.inject.spi,"
                            + "jakarta.enterprise.inject.spi.configurator,jakarta.enterprise.inject.literal,"
                            + "jakarta.enterprise.inject.se,jakarta.enterprise.event,"
                            + "jakarta.decorator\";version:List<Version>=\"2.0,1.2,1.1,1.0\"",
                    attrs.getValue("Provide-Capability"));
            assertEquals(
                    "osgi.serviceloader;"
                            + "filter:=\"(osgi.serviceloader=jakarta.enterprise.inject.se.SeContainerInitializer)\";"
                            + "cardinality:=multiple,osgi.serviceloader;"
                            + "filter:=\"(osgi.serviceloader=jakarta.enterprise.inject.spi.CDIProvider)\";"
                            + "cardinality:=multiple,osgi.extender;"
                            + "filter:=\"(osgi.extender=osgi.serviceloader.processor)\","
                            + "osgi.contract;osgi.contract=JavaEL;filter:=\"(&(osgi.contract=JavaEL)(version=2.2.0))\","
                            + "osgi.contract;osgi.contract=JavaInterceptor;"
                            + "filter:=\"(&(osgi.contract=JavaInterceptor)(version=1.2.0))\","
                            + "osgi.contract;osgi.contract=JavaInject;"
                            + "filter:=\"(&(osgi.contract=JavaInject)(version=1.0.0))\","
                            + "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version=1.8))\"",
                    attrs.getValue("Require-Capability"));
        }
    }

    @Test
    public void rewriteAdditionalAttributes() throws Exception {
        final Manifest manifest = new Manifest();
        final Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("description-custom", "This jar uses javax packages");

        List<Relocator> relocators = Collections.<Relocator>singletonList(new SimpleRelocator(
                "javax", "jakarta", Collections.<String>emptyList(), Collections.<String>emptyList()));

        transformer.setAdditionalAttributes(Arrays.asList("description-custom", "attribute-unknown"));
        final ByteArrayOutputStream out = transform(manifest, relocators);

        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            Attributes attrs = jis.getManifest().getMainAttributes();
            assertEquals("This jar uses jakarta packages", attrs.getValue("description-custom"));
        }
    }

    private ByteArrayOutputStream transform(final Manifest manifest, List<Relocator> relocators) throws IOException {
        final ByteArrayOutputStream mboas = new ByteArrayOutputStream();
        try (OutputStream mos = mboas) {
            manifest.write(mos);
        }
        transformer.processResource(
                JarFile.MANIFEST_NAME, new ByteArrayInputStream(mboas.toByteArray()), relocators, 0);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jarOutputStream = new JarOutputStream(out)) {
            transformer.modifyOutputStream(jarOutputStream);
        }
        return out;
    }
}
