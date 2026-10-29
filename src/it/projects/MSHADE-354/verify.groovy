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
import java.io.*;

// the shaded artifact must be in the configured outputDirectory, not the default build directory
File shaded = new File( basedir, "target/custom/mshade354-1.0.jar" );
System.out.println( "shaded artifact: " + shaded );
if ( !shaded.isFile() )
{
    System.out.println( "shaded artifact does not exist in the configured outputDirectory: " + shaded );
    return false;
}

// the pre-shade artifact must be preserved alongside it in the same outputDirectory
File original = new File( basedir, "target/custom/original-mshade354-1.0.jar" );
System.out.println( "original artifact: " + original );
if ( !original.isFile() )
{
    System.out.println( "original artifact was not preserved in the configured outputDirectory: " + original );
    return false;
}

// the shaded artifact must not be left behind in the default build directory
File defaultLocation = new File( basedir, "target/mshade354-1.0.jar" );
System.out.println( "default build directory artifact: " + defaultLocation );
if ( defaultLocation.isFile() )
{
    System.out.println( "shaded artifact was incorrectly left in the default build directory: " + defaultLocation );
    return false;
}

return true;
