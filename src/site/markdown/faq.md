---
title: Frequently Asked Questions
---

<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<a id="top"></a>

# Frequently Asked Questions

1. [Why Does My Second Shade Include The Results Of The First Execution?](#two-executions)
2. [Why Does Shade Report Hundreds Of Overlapping Classes When I Build Without `clean`?](#without-clean)

<a id="two-executions"></a>

### Why Does My Second Shade Include The Results Of The First Execution?

By default, shade replaces with original jar with the result of shading.
So, when a `pom.xml` includes two shades, the second shade execution will
(by default) start from the result of the first shade execution.

If you're looking for two independent shades then read in
[shade:shade](shade-mojo.html) about ways choose a different name for your
first shade.

<a id="without-clean"></a>

### Why Does Shade Report Hundreds Of Overlapping Classes When I Build Without `clean`?

When nothing changed since the last build, the Maven JAR Plugin keeps the existing JAR instead of creating it again.
Since shade replaced that JAR with the shaded one, the next build, for example `mvn install` right after `mvn verify`,
shades the shaded JAR again: every class of the dependencies is then found twice, and shade warns about each of them.

Build with `clean`, or make the JAR plugin create the JAR on every build with its
[`forceCreation`](/plugins/maven-jar-plugin/jar-mojo.html#forceCreation) parameter:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-jar-plugin</artifactId>
  <executions>
    <execution>
      <id>default-jar</id>
      <configuration>
        <forceCreation>true</forceCreation>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Renaming the shaded JAR with `finalName` also avoids the second shading, but then the project's main artifact, which
`install` and `deploy` publish, is the JAR without its dependencies.
