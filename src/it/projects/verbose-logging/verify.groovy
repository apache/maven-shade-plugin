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
def buildLog = new File(basedir, 'build.log')
assert buildLog.exists()

def content = buildLog.text
assert content.contains('[INFO] Including org.slf4j:slf4j-api:jar:1.7.36 in the shaded jar.')
assert content.contains('[INFO] Excluding org.slf4j:slf4j-simple:jar:1.7.36 from the shaded jar.')

return true
