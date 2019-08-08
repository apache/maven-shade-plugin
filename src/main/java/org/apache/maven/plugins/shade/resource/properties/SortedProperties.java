package org.apache.maven.plugins.shade.resource.properties;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Properties instance sorting its keys on iterations.
 */
public class SortedProperties extends Properties
{
    @Override
    public Set<Map.Entry<Object, Object>> entrySet()
    {
        final List<Map.Entry<Object, Object>> entries = new ArrayList<>( super.entrySet() );
        Collections.sort( entries, new Comparator<Map.Entry<Object, Object>>()
        {
            @Override
            public int compare( Map.Entry<Object, Object> o1, Map.Entry<Object, Object> o2 )
            {
                return String.valueOf( o1.getKey() ).compareTo( String.valueOf( o2.getKey() ) );
            }
        } );
        return new HashSet<>( entries );
    }

    @Override
    public synchronized Enumeration<Object> keys() // ensure it is sorted to be deterministic
    {
        final List<String> keys = new LinkedList<>();
        for ( Object k : super.keySet() )
        {
            keys.add( (String) k );
        }
        Collections.sort( keys );
        final Iterator<String> it = keys.iterator();
        return new Enumeration<Object>()
        {
            @Override
            public boolean hasMoreElements()
            {
                return it.hasNext();
            }

            @Override
            public Object nextElement()
            {
                return it.next();
            }
        };
    }
}
