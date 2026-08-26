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

import java.util.List;
import java.util.regex.Pattern;

/** @author Kamil Wójcik */
public class SerializedLambdaRelocator extends SimpleRelocator {
    private final Pattern serializedLambdaDefinitionPattern =
            Pattern.compile("\\(([BCDFIJSZ]|\\[+[BCDFIJSZ]|\\[*L[^;]+;)*\\)([BCDFIJSZ]|V|\\[+[BCDFIJSZ]|\\[*L[^;]+;)");
    private final Pattern clazzInsideFunctionDefintionPattern;
    private final String shadedPathPattern;
    private boolean shouldRelocate = true;

    public SerializedLambdaRelocator(
            String pattern, String shadedPattern, List<String> includes, List<String> excludes, boolean rawString) {
        super(pattern, shadedPattern, includes, excludes, rawString);
        if (shadedPattern != null && pattern != null) {
            this.shadedPathPattern = shadedPattern.replace('.', '/');
            String pathPattern = pattern.replace('.', '/');
            this.clazzInsideFunctionDefintionPattern = Pattern.compile("(?<=[(;)]L)" + Pattern.quote(pathPattern));
        } else {
            this.clazzInsideFunctionDefintionPattern = null;
            this.shadedPathPattern = null;
            this.shouldRelocate = false;
        }
    }

    @Override
    public boolean canRelocatePath(String path) {
        return shouldRelocate && serializedLambdaDefinitionPattern.matcher(path).matches();
    }

    @Override
    public boolean canRelocateClass(String clazz) {
        return false;
    }

    @Override
    public String relocatePath(String path) {
        return !shouldRelocate
                ? path
                : clazzInsideFunctionDefintionPattern.matcher(path).replaceAll(shadedPathPattern);
    }

    @Override
    public String relocateClass(String input) {
        return input;
    }

    @Override
    public String relocateAllClasses(String input) {
        return input;
    }

    @Override
    public String applyToSourceContent(String sourceContent) {
        return sourceContent;
    }
}
