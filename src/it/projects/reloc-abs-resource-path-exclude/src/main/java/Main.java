import java.net.URL;

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

public class Main
{

    public static void main( String[] args )
        throws Exception
    {
        new Main().testAbsResource();
    }

    private void testAbsResource() throws Exception
    {
        URL r = getClass().getResource("/org/apache/maven/from/a/test.properties");
        // expect NPE if from/a was shaded even though it was excluded
        System.out.println( r.toString() );

        r = getClass().getResource("/org/apache/maven/to/b/test.properties");
        // expect NPE if from/b was not shaded
        System.out.println( r.toString() );
    }

}
