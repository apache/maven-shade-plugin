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

<a id="two-executions"></a>

### Why Does My Second Shade Include The Results Of The First Execution?

By default, shade replaces with original jar with the result of shading.
So, when a `pom.xml` includes two shades, the second shade execution will
(by default) start from the result of the first shade execution.

If you're looking for two independent shades then read in
[shade:shade](shade-mojo.html) about ways choose a different name for your
first shade.
