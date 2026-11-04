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
package org.apache.maven.plugins.shade.relocation;

import java.util.Collections;

import org.apache.maven.plugins.shade.mojo.PackageRelocation;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.converters.lookup.ConverterLookup;
import org.codehaus.plexus.component.configurator.converters.lookup.DefaultConverterLookup;
import org.codehaus.plexus.component.configurator.expression.DefaultExpressionEvaluator;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class SimpleRelocatorParameterTest {

    @Test
    public void testThatNullShadedPatternInConstructorShouldNotThrowNullPointerException() {
        constructThenFailOnNullPointerException("", null);
    }

    @Test
    public void testNullShadedPatternDefaultsToHiddenPrefix() {
        SimpleRelocator relocator = new SimpleRelocator(
                "org.example", null, Collections.<String>emptyList(), Collections.<String>emptyList());

        assertEquals("hidden.org.example.Foo", relocator.relocateClass("org.example.Foo"));
        assertEquals("hidden/org/example/Foo.class", relocator.relocatePath("org/example/Foo.class"));
    }

    /**
     * An empty {@code <shadedPattern/>} in the POM reaches the relocator as {@code null}, which gives the default.
     */
    @Test
    public void testEmptyShadedPatternElementDefaultsToHiddenPrefix() throws ComponentConfigurationException {
        PlexusConfiguration configuration = new DefaultPlexusConfiguration("configuration");
        configuration.addChild("pattern", "org.example");
        configuration.addChild("shadedPattern", "");
        ConverterLookup lookup = new DefaultConverterLookup();
        PackageRelocation relocation = (PackageRelocation) lookup.lookupConverterForType(PackageRelocation.class)
                .fromConfiguration(
                        lookup,
                        configuration,
                        PackageRelocation.class,
                        PackageRelocation.class,
                        Thread.currentThread().getContextClassLoader(),
                        new DefaultExpressionEvaluator());

        SimpleRelocator relocator = new SimpleRelocator(
                relocation.getPattern(),
                relocation.getShadedPattern(),
                relocation.getIncludes(),
                relocation.getExcludes());

        assertEquals("hidden.org.example.Foo", relocator.relocateClass("org.example.Foo"));
    }

    private void constructThenFailOnNullPointerException(String pattern, String shadedPattern) {
        try {
            new SimpleRelocator(
                    pattern, shadedPattern, Collections.<String>emptyList(), Collections.<String>emptyList());
        } catch (NullPointerException e) {
            fail("Constructor should not throw null pointer exceptions");
        }
        // any other exception leads to test failure as well
    }
}
