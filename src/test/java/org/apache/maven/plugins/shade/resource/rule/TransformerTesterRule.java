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
package org.apache.maven.plugins.shade.resource.rule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ReproducibleResourceTransformer;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.converters.ConfigurationConverter;
import org.codehaus.plexus.component.configurator.converters.lookup.ConverterLookup;
import org.codehaus.plexus.component.configurator.converters.lookup.DefaultConverterLookup;
import org.codehaus.plexus.component.configurator.expression.DefaultExpressionEvaluator;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TransformerTesterRule implements TestRule {
    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                TransformerTest spec = description.getAnnotation(TransformerTest.class);
                if (spec == null) {
                    base.evaluate();
                    return;
                }

                Map<String, String> jar;
                try {
                    ReproducibleResourceTransformer transformer = createTransformer(spec);
                    visit(spec, transformer);
                    jar = captureOutput(transformer);
                } catch (Exception ex) {
                    if (Exception.class.isAssignableFrom(spec.expectedException())) {
                        assertTrue(
                                spec.expectedException().isAssignableFrom(ex.getClass()),
                                ex.getClass().getName());
                        return;
                    } else {
                        throw ex;
                    }
                }
                asserts(spec, jar);
            }
        };
    }

    private void asserts(TransformerTest spec, Map<String, String> jar) {
        if (spec.strictMatch() && jar.size() != spec.expected().length) {
            fail("Strict match test failed: " + jar);
        }
        for (final Resource expected : spec.expected()) {
            final String content = jar.get(expected.path());
            assertNotNull(content, expected.path());
            assertTrue(
                    content.replace(System.lineSeparator(), "\n").matches(expected.content()),
                    expected.path() + ", expected=" + expected.content() + ", actual=" + content);
        }
    }

    private Map<String, String> captureOutput(ReproducibleResourceTransformer transformer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            transformer.modifyOutputStream(jar);
        }

        Map<String, String> created = new HashMap<>();
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                created.put(entry.getName(), read(jar));
            }
        }
        return created;
    }

    private void visit(TransformerTest spec, ReproducibleResourceTransformer transformer) throws IOException {
        for (Resource resource : spec.visited()) {
            if (transformer.canTransformResource(resource.path())) {
                transformer.processResource(
                        resource.path(),
                        new ByteArrayInputStream(resource.content().getBytes(StandardCharsets.UTF_8)),
                        Collections.<Relocator>emptyList(),
                        0);
            }
        }
    }

    private String read(JarInputStream jar) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[512];
        int read;
        while ((read = jar.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read));
        }
        return builder.toString();
    }

    private ReproducibleResourceTransformer createTransformer(TransformerTest spec) {
        ConverterLookup lookup = new DefaultConverterLookup();
        try {
            ConfigurationConverter converter = lookup.lookupConverterForType(spec.transformer());
            PlexusConfiguration configuration = new DefaultPlexusConfiguration("configuration");
            for (Property property : spec.configuration()) {
                configuration.addChild(property.name(), property.value());
            }
            return (ReproducibleResourceTransformer) converter.fromConfiguration(
                    lookup,
                    configuration,
                    spec.transformer(),
                    spec.transformer(),
                    Thread.currentThread().getContextClassLoader(),
                    new DefaultExpressionEvaluator());
        } catch (ComponentConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Enables to describe a test without having to implement the logic itself.
     */
    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface TransformerTest {
        /**
         * @return the list of resource the transformer will process.
         */
        Resource[] visited();

        /**
         * @return the expected output created by the transformer.
         */
        Resource[] expected();

        /**
         * @return true if only expected resources must be found.
         */
        boolean strictMatch() default true;

        /**
         * @return type of transformer to use.
         */
        Class<?> transformer();

        /**
         * @return transformer configuration.
         */
        Property[] configuration();

        /**
         * @return if set to an exception class it ensures it is thrown during the processing.
         */
        Class<?> expectedException() default Object.class;
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface Property {
        String name();

        String value();
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface Resource {
        String path();

        String content();
    }
}
