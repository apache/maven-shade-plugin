package org.apache.maven.plugins.shade.resource.rule;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
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

public class TransformerTesterRule implements TestRule
{
    @Override
    public Statement apply( final Statement base, final Description description )
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                final TransformerTest spec = description.getAnnotation( TransformerTest.class );
                if ( spec == null )
                {
                    base.evaluate();
                    return;
                }

                final Map<String, String> jar;
                try
                {
                    final ResourceTransformer transformer = createTransformer(spec);
                    visit(spec, transformer);
                    jar = captureOutput(transformer);
                }
                catch ( final Exception ex )
                {
                    if ( Exception.class.isAssignableFrom( spec.expectedException() ) )
                    {
                        assertTrue(
                                ex.getClass().getName(),
                                spec.expectedException().isAssignableFrom( ex.getClass() ) );
                        return;
                    }
                    else
                    {
                        throw ex;
                    }
                }
                asserts(spec, jar);
            }
        };
    }

    private void asserts( final TransformerTest spec, final Map<String, String> jar)
    {
        if ( spec.strictMatch() && jar.size() != spec.expected().length )
        {
            fail( "Strict match test failed: " + jar );
        }
        for ( final Resource expected : spec.expected() )
        {
            final String content = jar.get( expected.path() );
            assertNotNull( expected.path(), content );
            assertTrue(
                    expected.path() + ", expected=" + expected.content() + ", actual=" + content,
                    content.replace( System.lineSeparator(), "\n" ) .matches( expected.content() ) );
        }
    }

    private Map<String, String> captureOutput(final ResourceTransformer transformer ) throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try ( final JarOutputStream jar = new JarOutputStream( out ) )
        {
            transformer.modifyOutputStream( jar );
        }

        final Map<String, String> created = new HashMap<>();
        try ( final JarInputStream jar = new JarInputStream( new ByteArrayInputStream( out.toByteArray() ) ) )
        {
            JarEntry entry;
            while ( ( entry = jar.getNextJarEntry() ) != null )
            {
                created.put( entry.getName(), read( jar ) );
            }
        }
        return created;
    }

    private void visit( final TransformerTest spec, final ResourceTransformer transformer ) throws IOException
    {
        for ( final Resource resource : spec.visited() )
        {
            if ( transformer.canTransformResource( resource.path() ))
            {
                transformer.processResource(
                        resource.path(),
                        new ByteArrayInputStream( resource.content().getBytes(StandardCharsets.UTF_8) ),
                        Collections.<Relocator>emptyList(), 0 );
            }
        }
    }

    private String read(final JarInputStream jar) throws IOException
    {
        final StringBuilder builder = new StringBuilder();
        final byte[] buffer = new byte[512];
        int read;
        while ( (read = jar.read(buffer) ) >= 0 )
        {
            builder.append( new String( buffer, 0, read ) );
        }
        return builder.toString();
    }

    private ResourceTransformer createTransformer(final TransformerTest spec)
    {
        final ConverterLookup lookup = new DefaultConverterLookup();
        try
        {
            final ConfigurationConverter converter = lookup.lookupConverterForType( spec.transformer() );
            final PlexusConfiguration configuration = new DefaultPlexusConfiguration( "configuration" );
            for ( final Property property : spec.configuration() )
            {
                configuration.addChild( property.name(), property.value() );
            }
            return ResourceTransformer.class.cast(
                    converter.fromConfiguration( lookup, configuration,  spec.transformer(), spec.transformer(),
                        Thread.currentThread().getContextClassLoader(),
                        new DefaultExpressionEvaluator() ) );
        }
        catch (final ComponentConfigurationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Enables to describe a test without having to implement the logic itself.
     */
    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface TransformerTest
    {
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
    public @interface Property
    {
        String name();
        String value();
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface Resource
    {
        String path();
        String content();
    }
}
