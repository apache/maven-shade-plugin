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

import java.security.MessageDigest
import java.util.jar.JarFile

String describeEntry(JarFile jar, String name, long time) {
    def entry = jar.getEntry(name)
    return String.format("  - %-47s: time = %d, lastModified = %s = diff %d min., extra = %b", name, entry.getTime(), entry.getLastModifiedTime().toString(),
           (long)((entry.getTime() / 1000L - time)/60L), entry.getExtra() != null)
}

String describeJar(JarFile jar) {
    return describeEntry(jar, "com/sun/jna/openbsd-x86-64/libjnidispatch.so", 1671223758L) + '\n'\
         + describeEntry(jar, "com/sun/jna/linux-loongarch64/libjnidispatch.so", 1671223358L)
}

String describe(String name) {
    def file = new File(basedir, "target/" + name)
    def jar = new JarFile(file)

    println(name)
    return "sha1 = " + MessageDigest.getInstance("SHA1").digest(file.bytes).encodeHex().toString()\
         + "\n" + describeJar(jar)
}

void describeTz(TimeZone tz) {
    println("TZ = " + tz.getID() + ", raw offset = " + tz.getRawOffset() / 60000 + " min., offset to current TZ = " + (tz.getRawOffset() - TimeZone.getDefault().getRawOffset()) / 60000 + " min.")
}

describeTz(TimeZone.getDefault())
println(describe("dependency/jna-5.13.0.jar"))
println(describe("current-OS.jar"))
def utcDescription = describe("UTC.jar")
describeTz(TimeZone.getTimeZone("Etc/UTC"))
println(utcDescription)
def tokyoDescription = describe("Tokyo.jar")
describeTz(TimeZone.getTimeZone("Asia/Tokyo"))
println(tokyoDescription)
describeTz(TimeZone.getTimeZone("Canada/Yukon"))
println(describe("Yukon.jar"))

assert utcDescription == tokyoDescription