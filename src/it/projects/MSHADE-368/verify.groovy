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

import java.util.jar.JarFile

def descriptorName = "META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule"
def shadedJar = new File(basedir, "target/mshade-368-1.0.jar")

assert shadedJar.isFile()

new JarFile(shadedJar).withCloseable { jar ->
    def descriptor = jar.getJarEntry(descriptorName)
    assert descriptor != null

    def properties = new Properties()
    jar.getInputStream(descriptor).withCloseable { properties.load(it) }

    assert properties.getProperty("moduleName") == "shaded-groovy-sql"
    assert properties.getProperty("moduleVersion") == "1.0"
    assert properties.getProperty("extensionClasses") == "org.apache.groovy.sql.extensions.SqlExtensions"
    assert properties.getProperty("staticExtensionClasses") == null
    assert jar.getJarEntry("org/apache/groovy/sql/extensions/SqlExtensions.class") != null
}
