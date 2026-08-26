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
package org.apache.maven.plugins.shade;

import javax.lang.model.SourceVersion;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Additional choices used while constructing the module descriptor for amalgamated shaded contents.
 */
public class ModuleInfoConfiguration {
    private String moduleName;

    private String publicBoundary = ModuleInfoPublicBoundary.PRIMARY.name();

    private Map<String, String> analysisJdkToolchain = Collections.emptyMap();

    private List<PackageDirective> additionalExports = Collections.emptyList();

    private List<PackageDirective> additionalOpens = Collections.emptyList();

    private List<Requirement> additionalRequires = Collections.emptyList();

    private Set<String> additionalUses = Collections.emptySet();

    private Set<String> dynamicUses = Collections.emptySet();

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    static boolean isValidModuleName(String moduleName) {
        if (moduleName == null || !SourceVersion.isName(moduleName)) {
            return false;
        }
        for (String identifier : moduleName.split("[.]", -1)) {
            if ("_".equals(identifier)) {
                return false;
            }
        }
        return true;
    }

    public ModuleInfoPublicBoundary getPublicBoundary() {
        return ModuleInfoPublicBoundary.fromString(publicBoundary);
    }

    public void setPublicBoundary(String publicBoundary) {
        this.publicBoundary = publicBoundary == null ? ModuleInfoPublicBoundary.PRIMARY.name() : publicBoundary;
    }

    public Map<String, String> getAnalysisJdkToolchain() {
        return analysisJdkToolchain;
    }

    public void setAnalysisJdkToolchain(Map<String, String> analysisJdkToolchain) {
        this.analysisJdkToolchain =
                analysisJdkToolchain == null ? Collections.<String, String>emptyMap() : analysisJdkToolchain;
    }

    public List<PackageDirective> getAdditionalExports() {
        return additionalExports;
    }

    public void setAdditionalExports(List<PackageDirective> additionalExports) {
        this.additionalExports =
                additionalExports == null ? Collections.<PackageDirective>emptyList() : additionalExports;
    }

    public List<PackageDirective> getAdditionalOpens() {
        return additionalOpens;
    }

    public void setAdditionalOpens(List<PackageDirective> additionalOpens) {
        this.additionalOpens = additionalOpens == null ? Collections.<PackageDirective>emptyList() : additionalOpens;
    }

    public List<Requirement> getAdditionalRequires() {
        return additionalRequires;
    }

    public void setAdditionalRequires(List<Requirement> additionalRequires) {
        this.additionalRequires =
                additionalRequires == null ? Collections.<Requirement>emptyList() : additionalRequires;
    }

    public Set<String> getAdditionalUses() {
        return additionalUses;
    }

    public void setAdditionalUses(Set<String> additionalUses) {
        this.additionalUses = additionalUses == null ? Collections.<String>emptySet() : additionalUses;
    }

    public Set<String> getDynamicUses() {
        return dynamicUses;
    }

    public void setDynamicUses(Set<String> dynamicUses) {
        this.dynamicUses = dynamicUses == null ? Collections.<String>emptySet() : dynamicUses;
    }

    /**
     * An additional export or open directive. An empty target set denotes an unqualified directive.
     */
    public static class PackageDirective {
        private String packageName;

        private Set<String> targets = Collections.emptySet();

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public void setPackage(String packageName) {
            this.packageName = packageName;
        }

        public Set<String> getTargets() {
            return targets;
        }

        public void setTargets(Set<String> targets) {
            this.targets = targets == null ? Collections.<String>emptySet() : targets;
        }
    }

    /**
     * An additional module requirement.
     */
    public static class Requirement {
        private String module;

        private boolean staticRequirement;

        private boolean transitive;

        public String getModule() {
            return module;
        }

        public void setModule(String module) {
            this.module = module;
        }

        public boolean isStaticRequirement() {
            return staticRequirement;
        }

        public void setStaticRequirement(boolean staticRequirement) {
            this.staticRequirement = staticRequirement;
        }

        public void setStatic(boolean staticRequirement) {
            this.staticRequirement = staticRequirement;
        }

        public boolean isTransitive() {
            return transitive;
        }

        public void setTransitive(boolean transitive) {
            this.transitive = transitive;
        }
    }
}
