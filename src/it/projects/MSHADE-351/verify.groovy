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

JarFile jarFile = new JarFile(new File(basedir, "target/test-1.0.jar"))
try {
    def load = { String name ->
        def entry = jarFile.getJarEntry(name)
        assert entry != null : name + " is missing from the shaded jar"
        def properties = new Properties()
        jarFile.getInputStream(entry).withCloseable { properties.load(it) }
        return properties
    }

    // Microprofile Config: the default ordinal is 1000, so the 2000 of "one" wins over "two"
    def microprofile = load("META-INF/microprofile-config.properties")
    assert microprofile.getProperty("shared") == "one" : microprofile
    assert microprofile.getProperty("only.one") == "1" : microprofile
    assert microprofile.getProperty("only.two") == "2" : microprofile
    assert microprofile.getProperty("config_ordinal") == null : microprofile

    // OpenWebBeans: the default ordinal is 100, so "two" wins over the 50 of "one"
    def openWebBeans = load("META-INF/openwebbeans/openwebbeans.properties")
    assert openWebBeans.getProperty("shared") == "two" : openWebBeans
    assert openWebBeans.getProperty("only.one") == "1" : openWebBeans
    assert openWebBeans.getProperty("only.two") == "2" : openWebBeans
    assert openWebBeans.getProperty("configuration.ordinal") == null : openWebBeans
} finally {
    jarFile.close()
}
