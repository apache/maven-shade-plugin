package org.apache.maven.plugins.shade.resource;

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

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.codehaus.plexus.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Merges <code>META-INF/NOTICE.TXT</code> files.
 */
public class ApacheNoticeResourceTransformer
    extends AbstractCompatibilityTransformer
{
    Set<String> entries = new LinkedHashSet<>();

    Map<String, Set<String>> organizationEntries = new LinkedHashMap<>();

    String projectName = ""; // MSHADE-101 :: NullPointerException when projectName is missing

    boolean addHeader = true;

    String preamble1 = "// ------------------------------------------------------------------\n"
        + "// NOTICE file corresponding to the section 4d of The Apache License,\n"
        + "// Version 2.0, in this case for ";

    String preamble2 = "\n// ------------------------------------------------------------------\n";

    String preamble3 = "This product includes software developed at\n";

    //defaults overridable via config in pom
    String organizationName = "The Apache Software Foundation";

    String organizationURL = "http://www.apache.org/";

    String inceptionYear = "2006";

    String copyright;

    /**
     * The file encoding of the <code>NOTICE</code> file.
     */
    String encoding;

    private long time = Long.MIN_VALUE;

    private static final String NOTICE_PATH = "META-INF/NOTICE";

    private static final String NOTICE_TXT_PATH = "META-INF/NOTICE.txt";
    
    private static final String NOTICE_MD_PATH = "META-INF/NOTICE.md";

    public boolean canTransformResource( String resource )
    {
        return NOTICE_PATH.equalsIgnoreCase( resource ) 
            || NOTICE_TXT_PATH.equalsIgnoreCase( resource ) 
            || NOTICE_MD_PATH.equalsIgnoreCase( resource );

    }

    public void processResource( String resource, InputStream is, List<Relocator> relocators, long time )
        throws IOException
    {
        if ( entries.isEmpty() )
        {
            String year = new SimpleDateFormat( "yyyy" ).format( new Date() );
            if ( !inceptionYear.equals( year ) )
            {
                year = inceptionYear + "-" + year;
            }

            //add headers
            if ( addHeader )
            {
                entries.add( preamble1 + projectName + preamble2 );
            }
            else
            {
                entries.add( "" );
            }
            //fake second entry, we'll look for a real one later
            entries.add( projectName + "\nCopyright " + year + " " + organizationName + "\n" );
            entries.add( preamble3 + organizationName + " (" + organizationURL + ").\n" );
        }

        BufferedReader reader;
        if ( StringUtils.isNotEmpty( encoding ) )
        {
            reader = new BufferedReader( new InputStreamReader( is, encoding ) );
        }
        else
        {
            reader = new BufferedReader( new InputStreamReader( is ) );
        }

        String line = reader.readLine();
        StringBuilder sb = new StringBuilder();
        Set<String> currentOrg = null;
        int lineCount = 0;
        while ( line != null )
        {
            String trimedLine = line.trim();

            if ( !trimedLine.startsWith( "//" ) )
            {
                if ( trimedLine.length() > 0 )
                {
                    if ( trimedLine.startsWith( "- " ) )
                    {
                        //resource-bundle 1.3 mode
                        if ( lineCount == 1
                            && sb.toString().contains( "This product includes/uses software(s) developed by" ) )
                        {
                            currentOrg = organizationEntries.get( sb.toString().trim() );
                            if ( currentOrg == null )
                            {
                                currentOrg = new TreeSet<>();
                                organizationEntries.put( sb.toString().trim(), currentOrg );
                            }
                            sb = new StringBuilder();
                        }
                        else if ( sb.length() > 0 && currentOrg != null )
                        {
                            currentOrg.add( sb.toString() );
                            sb = new StringBuilder();
                        }

                    }
                    sb.append( line ).append( "\n" );
                    lineCount++;
                }
                else
                {
                    String ent = sb.toString();
                    if ( ent.startsWith( projectName ) && ent.contains( "Copyright " ) )
                    {
                        copyright = ent;
                    }
                    if ( currentOrg == null )
                    {
                        entries.add( ent );
                    }
                    else
                    {
                        currentOrg.add( ent );
                    }
                    sb = new StringBuilder();
                    lineCount = 0;
                    currentOrg = null;
                }
            }

            line = reader.readLine();
        }
        if ( sb.length() > 0 )
        {
            if ( currentOrg == null )
            {
                entries.add( sb.toString() );
            }
            else
            {
                currentOrg.add( sb.toString() );
            }
        }
        if ( time > this.time )
        {
            this.time = time;        
        }
    }

    public boolean hasTransformedResource()
    {
        return true;
    }

    public void modifyOutputStream( JarOutputStream jos )
        throws IOException
    {
        JarEntry jarEntry = new JarEntry( NOTICE_PATH );
        jarEntry.setTime( time );
        jos.putNextEntry( jarEntry );

        Writer writer;
        if ( StringUtils.isNotEmpty( encoding ) )
        {
            writer = new OutputStreamWriter( jos, encoding );
        }
        else
        {
            writer = new OutputStreamWriter( jos );
        }

        int count = 0;
        for ( String line : entries )
        {
            ++count;
            if ( line.equals( copyright ) && count != 2 )
            {
                continue;
            }

            if ( count == 2 && copyright != null )
            {
                writer.write( copyright );
                writer.write( '\n' );
            }
            else
            {
                writer.write( line );
                writer.write( '\n' );
            }
            if ( count == 3 )
            {
                //do org stuff
                for ( Map.Entry<String, Set<String>> entry : organizationEntries.entrySet() )
                {
                    writer.write( entry.getKey() );
                    writer.write( '\n' );
                    for ( String l : entry.getValue() )
                    {
                        writer.write( l );
                    }
                    writer.write( '\n' );
                }
            }
        }

        writer.flush();

        entries.clear();
    }
}
