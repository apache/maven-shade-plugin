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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.plexus.util.SelectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class SimpleRelocator implements Relocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleRelocator.class);
    /**
     * Match dot, slash or space at end of string
     */
    private static final Pattern RX_ENDS_WITH_DOT_SLASH_SPACE = Pattern.compile("[./ ]$");

    /**
     * Match <ul>
     *     <li>certain Java keywords + space</li>
     *     <li>beginning of Javadoc link + optional line breaks and continuations with '*'</li>
     *     <li>(opening curly brace / opening parenthesis / comma / equals / semicolon) + space</li>
     *     <li>(closing curly brace / closing multi-line comment) + space</li>
     * </ul>
     * at end of string
     */
    private static final Pattern RX_ENDS_WITH_JAVA_KEYWORD = Pattern.compile(
            "\\b(import|package|public|protected|private|static|final|synchronized|abstract|volatile|extends|implements|throws) $"
                    + "|"
                    + "\\{@link( \\*)* $"
                    + "|"
                    + "([{}(=;,]|\\*/) $");

    /** plain text, no wildcards, always refers to fully qualified package names, may be {@code null} when rawString is {@code true}. */
    private final String originalPattern;

    /** plain text, no wildcards, always refers to paths with "/" separator, never {@code null}. */
    private final String originalPathPattern;

    /**
     * May be {@code null} when {@link #rawString} is true
     */
    private final Pattern regExPattern;

    private final Pattern regExPathPattern;

    /**
     * Replacement (no wildcards) for {@link #originalPattern}, may be {@code null} when {@link #rawString} is true.
     */
    private final String shadedPattern;

    /**
     * Replacement (no wildcards) for {@link #originalPathPattern}.
     */
    private final String shadedPathPattern;

    /**
     * Patterns to include in relocation.
     * Only Ant-based patterns (used with SelectorUtils.matchPath), must not start with "%ant[" prefix. Include both forms with dots and slashes, e.g. "my/package/**" and "my/package/*" for class patterns, "my/path/**" and "my/path/*" for path patterns.
     */
    private final Set<String> includes;

    /**
     * Patterns to exclude from relocation.
     * Only Ant-based patterns (used with SelectorUtils.matchPath), must not start with "%ant[" prefix. Include both forms with dots and slashes, e.g. "my/package/**" and "my/package/*" for class patterns, "my/path/**" and "my/path/*" for path patterns.
     */
    private final Set<String> excludes;

    // prefix (no wildcards), derived from excludes
    private final Set<String> sourcePackageExcludes = new LinkedHashSet<>();

    // prefix (no wildcards), derived from excludes
    private final Set<String> sourcePathExcludes = new LinkedHashSet<>();

    private final boolean rawString;

    /**
     * Same as {@link #SimpleRelocator(String, String, List, List, boolean)} with {@code rawString} set to {@code false}.
     * @param patt
     * @param shadedPattern
     * @param includes
     * @param excludes
     */
    public SimpleRelocator(String patt, String shadedPattern, List<String> includes, List<String> excludes) {
        this(patt, shadedPattern, includes, excludes, false);
    }

    /**
     * Creates a relocator with the given patterns and includes/excludes. If {@code rawString} is {@code true}, then the given pattern is
     * treated as regular expression pattern, otherwise it is treated as plain text with no wildcards.
     * In the latter case, the pattern is expected to refer to classes, not paths, and the constructor will derive the path pattern by replacing dots with slashes.
     * @param pattern
     * @param shadedPattern
     * @param includes
     * @param excludes
     * @param rawString
     */
    public SimpleRelocator(
            String pattern, String shadedPattern, List<String> includes, List<String> excludes, boolean rawString) {
        this.rawString = rawString;
        if (rawString) {
            originalPathPattern = pattern;
            this.shadedPathPattern = shadedPattern;

            originalPattern = null; // not used for raw string relocator
            this.shadedPattern = null; // not used for raw string relocator
        } else {
            if (pattern == null) {
                // means default package
                throw new IllegalArgumentException(
                        "Pattern must not be null, otherwise it is unclear what to relocate!");
            } else {
                originalPattern = pattern.replace('/', '.');
                originalPathPattern = pattern.replace('.', '/');
            }

            if (shadedPattern != null) {
                this.shadedPattern = shadedPattern.replace('/', '.');
                this.shadedPathPattern = shadedPattern.replace('.', '/');
            } else {
                this.shadedPattern = "hidden." + originalPattern;
                this.shadedPathPattern = "hidden/" + originalPathPattern;
            }
        }

        if (rawString) {
            if ((includes != null && !includes.isEmpty()) || (excludes != null && !excludes.isEmpty())) {
                LOGGER.warn("Includes and excludes are ignored when rawString is true");
            }
            // In raw string mode, the pattern is treated as regular expression, so we compile it as is, without quoting
            // it.
            this.regExPattern = originalPattern != null ? Pattern.compile(originalPattern) : null;
            this.regExPathPattern = Pattern.compile(originalPathPattern);
            this.includes = null;
            this.excludes = null;
        } else {
            this.includes = normalizePatterns(includes);
            this.excludes = normalizePatterns(excludes);

            // Don't replace all dots to slashes, otherwise /META-INF/maven/${groupId} can't be matched.
            if (includes != null && !includes.isEmpty()) {
                this.includes.addAll(includes);
            }

            if (excludes != null && !excludes.isEmpty()) {
                this.excludes.addAll(excludes);
            }

            if (this.excludes != null) {
                // Create exclude pattern sets for sources
                for (String exclude : this.excludes) {
                    // Excludes should be subpackages of the global pattern
                    if (exclude.startsWith(originalPattern)) {
                        sourcePackageExcludes.add(
                                exclude.substring(originalPattern.length()).replaceFirst("[.][*]$", ""));
                    }
                    // Excludes should be subpackages of the global pattern
                    if (exclude.startsWith(originalPathPattern)) {
                        sourcePathExcludes.add(
                                exclude.substring(originalPathPattern.length()).replaceFirst("[/][*]$", ""));
                    }
                }
            }
            this.regExPattern = originalPattern != null ? Pattern.compile(Pattern.quote(originalPattern)) : null;
            this.regExPathPattern = Pattern.compile(Pattern.quote(originalPathPattern));
        }
    }

    /**
     * Normalizes the given patterns by replacing dots with slashes and slashes with dots so that both forms are returned.
     *
     * @param patterns
     * @return the normalized patterns, or {@code null} if the given patterns were {@code null} or empty
     */
    private static Set<String> normalizePatterns(Collection<String> patterns) {
        Set<String> normalized = null;

        if (patterns != null && !patterns.isEmpty()) {
            normalized = new LinkedHashSet<>();
            for (String pattern : patterns) {
                String classPattern = pattern.replace('.', '/');
                normalized.add(classPattern);
                // Actually, class patterns should just use 'foo.bar.*' ending with a single asterisk, but some users
                // mistake them for path patterns like 'my/path/**', so let us be a bit more lenient here.
                if (classPattern.endsWith("/*") || classPattern.endsWith("/**")) {
                    String packagePattern = classPattern.substring(0, classPattern.lastIndexOf('/'));
                    normalized.add(packagePattern);
                }
            }
        }

        return normalized;
    }

    private boolean isIncluded(String path) {
        if (includes != null && !includes.isEmpty()) {
            for (String include : includes) {
                if (SelectorUtils.matchPath(include, path, true)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean isExcluded(String path) {
        if (excludes != null && !excludes.isEmpty()) {
            for (String exclude : excludes) {
                if (SelectorUtils.matchPath(exclude, path, true)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean canRelocatePath(String path) {
        if (rawString) {
            return regExPathPattern.matcher(path).find();
        }

        if (path.endsWith(".class")) {
            path = path.substring(0, path.length() - 6);
        }

        // Allow for annoying option of an extra / on the front of a path. See MSHADE-119; comes from
        // getClass().getResource("/a/b/c.properties").
        if (!path.isEmpty() && path.charAt(0) == '/') {
            path = path.substring(1);
        }

        if (isIncluded(path) && !isExcluded(path)) {
            Matcher matcher = regExPathPattern.matcher(path);
            if (matcher.find()) {
                return matcher.start() == 0;
            }
        }
        return false;
    }

    @Override
    public boolean canRelocateClass(String clazz) {
        return !rawString && clazz.indexOf('/') < 0 && canRelocatePath(clazz.replace('.', '/'));
    }

    @Override
    public String relocatePath(String path) {
        if (rawString) {
            return regExPathPattern.matcher(path).replaceAll(shadedPathPattern);
        } else {
            return regExPathPattern.matcher(path).replaceFirst(shadedPathPattern);
        }
    }

    @Override
    public String relocateClass(String input) {
        return regExPattern == null ? input : regExPattern.matcher(input).replaceFirst(shadedPattern);
    }

    @Override
    public String relocateAllClasses(String input) {
        return regExPattern == null ? input : regExPattern.matcher(input).replaceAll(shadedPattern);
    }

    @Override
    public String applyToSourceContent(String sourceContent) {
        if (rawString) {
            return sourceContent;
        }
        sourceContent = shadeSourceWithExcludes(sourceContent, originalPattern, shadedPattern, sourcePackageExcludes);
        return shadeSourceWithExcludes(sourceContent, originalPathPattern, shadedPathPattern, sourcePathExcludes);
    }

    private String shadeSourceWithExcludes(
            String sourceContent, String patternFrom, String patternTo, Set<String> excludedPatterns) {
        // Usually shading makes package names a bit longer, so make buffer 10% bigger than original source
        StringBuilder shadedSourceContent = new StringBuilder(sourceContent.length() * 11 / 10);
        boolean isFirstSnippet = true;
        // Make sure that search pattern starts at word boundary and that we look for literal ".", not regex jokers
        String[] snippets = sourceContent.split("\\b" + patternFrom.replace(".", "[.]") + "\\b");
        for (int i = 0, snippetsLength = snippets.length; i < snippetsLength; i++) {
            String snippet = snippets[i];
            String previousSnippet = isFirstSnippet ? "" : snippets[i - 1];
            boolean doExclude = false;
            for (String excludedPattern : excludedPatterns) {
                if (snippet.startsWith(excludedPattern)) {
                    doExclude = true;
                    break;
                }
            }
            if (isFirstSnippet) {
                shadedSourceContent.append(snippet);
                isFirstSnippet = false;
            } else {
                String previousSnippetOneLine = previousSnippet.replaceAll("\\s+", " ");
                boolean afterDotSlashSpace = RX_ENDS_WITH_DOT_SLASH_SPACE
                        .matcher(previousSnippetOneLine)
                        .find();
                boolean afterJavaKeyWord = RX_ENDS_WITH_JAVA_KEYWORD
                        .matcher(previousSnippetOneLine)
                        .find();
                boolean shouldExclude = doExclude || afterDotSlashSpace && !afterJavaKeyWord;
                shadedSourceContent
                        .append(shouldExclude ? patternFrom : patternTo)
                        .append(snippet);
            }
        }
        return shadedSourceContent.toString();
    }
}
