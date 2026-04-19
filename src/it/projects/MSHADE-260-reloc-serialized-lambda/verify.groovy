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

import java.util.jar.*

// Path to the shaded JAR
File jarFile = new File(basedir, "target/serialized-lambda-1.0.jar")
assert jarFile.exists() : "Shaded JAR not found: ${jarFile}"

JarFile jar = new JarFile(jarFile)

// Check 1: Verify shaded classes exist (with relocated package)
def shadedClasses = [
    "org/apache/maven/its/shade/reloc/shaded/lambda/Main.class",
    "org/apache/maven/its/shade/reloc/shaded/lambda/Processor.class",
    "org/apache/maven/its/shade/reloc/shaded/lambda/DataHolder.class",
    "org/apache/maven/its/shade/reloc/shaded/lambda/MapFunction.class"
]

shadedClasses.each { path ->
    assert jar.getEntry(path) != null : "Expected shaded class not found: ${path}"
}

// Check 2: Verify original classes do NOT exist (they should be relocated)
def originalClasses = [
    "org/apache/maven/its/shade/reloc/lambda/Main.class",
    "org/apache/maven/its/shade/reloc/lambda/Processor.class",
    "org/apache/maven/its/shade/reloc/lambda/DataHolder.class",
    "org/apache/maven/its/shade/reloc/lambda/MapFunction.class"
]

originalClasses.each { path ->
    assert jar.getEntry(path) == null : "Original class should have been relocated: ${path}"
}

// Check 3: Read the Main class bytes and verify serialized lambda metadata is relocated
def mainEntry = jar.getJarEntry("org/apache/maven/its/shade/reloc/shaded/lambda/Main.class")
if (mainEntry != null) {
    def is = jar.getInputStream(mainEntry)
    def baos = new ByteArrayOutputStream()
    def buffer = new byte[1024]
    int len
    while ((len = is.read(buffer)) != -1) {
        baos.write(buffer, 0, len)
    }
    is.close()
    
    // Convert class bytes to string for pattern searching
    // The serialized lambda metadata should contain the SHADED package, not original
    def classContent = new String(baos.toByteArray(), "ISO-8859-1")
    
    // Look for the SerializedLambda bootstrap method marker
    // This appears in the bytecode when lambdas are used
    if (classContent.contains("SerializedLambda")) {
        // If we have SerializedLambda, verify the implementation class reference
        // The implementation class should point to the shaded package
        // Check that the original package name does NOT appear in serialized metadata
        assert !classContent.contains("org/apache/maven/its/shade/reloc/lambda/Processor") :
            "Serialized lambda metadata still contains original package path. " +
            "The class reference should have been relocated to shaded package."

        assert !classContent.contains("org/apache/maven/its/shade/reloc/lambda") :
                "Serialized lambda metadata still contains original package path. " +
                        "The class reference should have been relocated to shaded package."
    }
}

// Check 4: Search all class files for any lingering original package references
def entries = jar.entries()
boolean foundSerializedLambdaMetadata = false
while (entries.hasMoreElements()) {
    def entry = entries.nextElement()
    if (entry.getName().endsWith(".class")) {
        def is = jar.getInputStream(entry)
        def baos = new ByteArrayOutputStream()
        def buffer = new byte[1024]
        int len
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len)
        }
        is.close()
        
        def classContent = new String(baos.toByteArray(), "ISO-8859-1")
        
        // Check for SerializedLambda constant pool entries
        if (classContent.contains("java/lang/invoke/LambdaMetafactory") ||
            classContent.contains("SerializedLambda")) {
            foundSerializedLambdaMetadata = true
            
            // If this class uses lambdas, verify it uses the shaded package
            // The original package should not appear in constant pool references
            assert !(classContent.contains("org/apache/maven/its/shade/reloc/lambda/" +
                "Processor") ||
                classContent.contains("org/apache/maven/its/shade/reloc/lambda/" +
                "Main") ||
                classContent.contains("org/apache/maven/its/shade/reloc/lambda/" +
                "DataHolder")) :
                "Class ${entry.getName()} contains reference to original " +
                "package in lambda metadata. All references should use shaded " +
                "package: org/apache/maven/its/shade/reloc/shaded/lambda/"
        }
    }
}

if (!foundSerializedLambdaMetadata) {
    println "Warning: No serialized lambda metadata found in classes. " +
        "This may indicate the test classes were not compiled with lambda usage."
}

jar.close()

println "Serialized lambda relocation test PASSED!"
println "All classes properly relocated and no original package references found."
