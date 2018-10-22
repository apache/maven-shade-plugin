package org.apache.maven.plugins.shade.relocation;

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

import org.codehaus.plexus.util.SelectorUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class SimpleRelocator
    implements Relocator
{

    private final String pattern;

    private final String pathPattern;

    private final String shadedPattern;

    private final String shadedPathPattern;

    private final Set<String> includes;

    private final Set<String> excludes;

    private final boolean rawString;

    public SimpleRelocator( String patt, String shadedPattern, List<String> includes, List<String> excludes )
    {
        this( patt, shadedPattern, includes, excludes, false );
    }

    public SimpleRelocator( String patt, String shadedPattern, List<String> includes, List<String> excludes,
                            boolean rawString )
    {
        this.rawString = rawString;

        if ( rawString )
        {
            this.pathPattern = patt;
            this.shadedPathPattern = shadedPattern;

            this.pattern = null; // not used for raw string relocator
            this.shadedPattern = null; // not used for raw string relocator
        }
        else
        {
            if ( patt == null )
            {
                this.pattern = "";
                this.pathPattern = "";
            }
            else
            {
                this.pattern = patt.replace( '/', '.' );
                this.pathPattern = patt.replace( '.', '/' );
            }

            if ( shadedPattern != null )
            {
                this.shadedPattern = shadedPattern.replace( '/', '.' );
                this.shadedPathPattern = shadedPattern.replace( '.', '/' );
            }
            else
            {
                this.shadedPattern = "hidden." + this.pattern;
                this.shadedPathPattern = "hidden/" + this.pathPattern;
            }
        }

        this.includes = normalizePatterns( includes );
        this.excludes = normalizePatterns( excludes );

        // Don't replace all dots to slashes, otherwise /META-INF/maven/${groupId} can't be matched.
        if ( includes != null && !includes.isEmpty() )
        {
            this.includes.addAll( includes );
        }
        
        if ( excludes != null && !excludes.isEmpty() )
        {
            this.excludes.addAll( excludes );
        }
    }

    private static Set<String> normalizePatterns( Collection<String> patterns )
    {
        Set<String> normalized = null;

        if ( patterns != null && !patterns.isEmpty() )
        {
            normalized = new LinkedHashSet<>();

            for ( String pattern : patterns )
            {

                String classPattern = pattern.replace( '.', '/' );

                normalized.add( classPattern );

                if ( classPattern.endsWith( "/*" ) )
                {
                    String packagePattern = classPattern.substring( 0, classPattern.lastIndexOf( '/' ) );
                    normalized.add( packagePattern );
                }
            }
        }

        return normalized;
    }

    private boolean isIncluded( String path )
    {
        if ( includes != null && !includes.isEmpty() )
        {
            for ( String include : includes )
            {
                if ( SelectorUtils.matchPath( include, path, true ) )
                {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean isExcluded( String path )
    {
        if ( excludes != null && !excludes.isEmpty() )
        {
            for ( String exclude : excludes )
            {
                if ( SelectorUtils.matchPath( exclude, path, true ) )
                {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean canRelocatePath( String path )
    {
        if ( rawString )
        {
            return Pattern.compile( pathPattern ).matcher( path ).find();
        }

        if ( path.endsWith( ".class" ) )
        {
            path = path.substring( 0, path.length() - 6 );
        }

        // Allow for annoying option of an extra / on the front of a path. See MSHADE-119; comes from
        // getClass().getResource("/a/b/c.properties").
        if ( !path.isEmpty() && path.charAt( 0 ) == '/' )
        {
            path = path.substring( 1 );
        }

        return isIncluded( path ) && !isExcluded( path ) && path.startsWith( pathPattern );
    }

    public boolean canRelocateClass( String clazz )
    {
        return !rawString && clazz.indexOf( '/' ) < 0 && canRelocatePath( clazz.replace( '.', '/' ) );
    }

    public String relocatePath( String path )
    {
        if ( rawString )
        {
            return path.replaceAll( pathPattern, shadedPathPattern );
        }
        else
        {
            return path.replaceFirst( pathPattern, shadedPathPattern );
        }
    }

    public String relocateClass( String clazz )
    {
        return clazz.replaceFirst( pattern, shadedPattern );
    }

    public String applyToSourceContent( String sourceContent )
    {
        if ( rawString )
        {
            return sourceContent;
        }
        else
        {
            return sourceContent.replaceAll( "\\b" + pattern, shadedPattern );
        }
    }
}
