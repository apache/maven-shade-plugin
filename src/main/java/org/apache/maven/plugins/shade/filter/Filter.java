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
package org.apache.maven.plugins.shade.filter;

import java.io.File;

/**
 * @author David Blevins
 */
public interface Filter {
    /**
     * @param jar the jar file
     * @return true if we can filter false otherwise
     */
    boolean canFilter(File jar);

    /**
     * @param classFile the classFile
     * @return true if the file has been filtered false otherwise
     */
    boolean isFiltered(String classFile);

    /**
     * If we are finished.
     */
    void finished();
}
