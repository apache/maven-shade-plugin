package org.apache.maven.plugins.shade.resource.properties.io;

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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * Simple buffered writer skipping its first write(String) call.
 */
public class SkipPropertiesDateLineWriter extends BufferedWriter
{
    private State currentState = State.MUST_SKIP_DATE_COMMENT;

    public SkipPropertiesDateLineWriter( Writer out )
    {
        super( out );
    }

    @Override
    public void write( String str ) throws IOException
    {
        if ( currentState.shouldSkip( str ) )
        {
            currentState = currentState.next();
            return;
        }
        super.write( str );
    }

    private enum State
    {
        MUST_SKIP_DATE_COMMENT
        {
            @Override
            boolean shouldSkip( String content )
            {
                return content.length() > 1 && content.startsWith( "#" ) && !content.startsWith( "# " );
            }

            @Override
            State next()
            {
                return SKIPPED_DATE_COMMENT;
            }
        },
        SKIPPED_DATE_COMMENT
        {
            @Override
            boolean shouldSkip( String content )
            {
                return content.trim().isEmpty();
            }

            @Override
            State next()
            {
                return DONE;
            }
        },
        DONE
        {
            @Override
            boolean shouldSkip( String content )
            {
                return false;
            }

            @Override
            State next()
            {
                throw new UnsupportedOperationException( "done is a terminal state" );
            }
        };

        abstract boolean shouldSkip( String content );
        abstract State next();
    }
}
