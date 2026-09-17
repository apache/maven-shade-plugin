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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ServicesResourceTransformer;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;

/**
 * Collects, merges and writes module descriptors after the shaded archive contents are known.
 */
final class ModuleInfoProcessor {
    private static final Pattern VERSIONED_MODULE_INFO =
            Pattern.compile("^META-INF/versions/([1-9][0-9]*)/module-info[.]class$");

    private static final Pattern VERSIONED_CLASS = Pattern.compile("^META-INF/versions/([1-9][0-9]*)/(.+)[.]class$");

    private static final Pattern SERVICE_CONFIGURATION = Pattern.compile("^META-INF/services/([^/]+)$");

    private static final int FIRST_MODULE_RELEASE = 9;

    private final Logger logger;

    private final File primaryArtifact;

    private final List<Relocator> relocators;

    private final ModuleInfoConfiguration configuration;

    private final File analysisJdkHome;

    private final Map<File, ArtifactData> artifacts = new LinkedHashMap<>();

    private final Map<File, ArtifactData> dependencyArtifacts = new LinkedHashMap<>();

    private final Map<Integer, Set<String>> outputClasses = new TreeMap<>();

    private final Map<Integer, Set<String>> outputPackages = new TreeMap<>();

    private final Map<File, Map<Integer, Set<String>>> artifactOutputPackages = new LinkedHashMap<>();

    private final Map<Integer, Map<String, RetainedClass>> retainedClasses = new TreeMap<>();

    private final Map<ServiceProvider, Origin> providerOrigins = new HashMap<>();

    private final Map<String, Origin> transitiveRequirementOrigins = new HashMap<>();

    private final Set<String> warnedDroppedAttributes = new HashSet<>();

    private final Set<File> warnedAutomaticAnalysis = new HashSet<>();

    private final Set<File> warnedDynamicUses = new HashSet<>();

    private String primaryModuleName;

    private String outputModuleName;

    private boolean multiReleaseOutput;

    private int modularFloor;

    private List<FloorReason> floorReasons = Collections.emptyList();

    private PlatformModuleIndex platformModules;

    ModuleInfoProcessor(ShadeRequest request, Logger logger) throws IOException, MojoExecutionException {
        this.logger = logger;
        this.primaryArtifact = request.getPrimaryArtifact();
        this.relocators = request.getRelocators();
        this.configuration = request.getModuleInfoConfiguration();
        this.analysisJdkHome = request.getModuleInfoAnalysisJdkHome();
        for (File artifact : request.getJars()) {
            ArtifactData data = scanArtifact(artifact);
            artifacts.put(artifact, data);
            multiReleaseOutput |= data.multiRelease;
        }
        for (File artifact : request.getDependencyAnalysisArtifacts()) {
            if (!artifacts.containsKey(artifact) && artifact != null && artifact.exists()) {
                try {
                    dependencyArtifacts.put(artifact, scanArtifact(artifact, false));
                } catch (ZipException e) {
                    logger.debug("Skipping non-archive dependency-analysis input " + artifact);
                }
            }
        }
        determinePrimaryModuleName();
        determineOutputModuleName();
        multiReleaseOutput |= primaryRootDescriptorRequiresVersionedOutput();
    }

    static boolean isModuleInfo(String name) {
        return "module-info.class".equals(name)
                || VERSIONED_MODULE_INFO.matcher(name).matches();
    }

    static boolean isServiceConfiguration(String name) {
        return SERVICE_CONFIGURATION.matcher(name).matches();
    }

    String getOutputModuleName() {
        return outputModuleName;
    }

    boolean hasPrimaryModule() {
        return primaryModuleName != null;
    }

    void selectDescriptors(File artifact, List<Filter> filters) {
        ArtifactData data = artifacts.get(artifact);
        if (data != null) {
            for (DescriptorEntry descriptor : data.descriptorsByName.values()) {
                descriptor.included = true;
                for (Filter filter : filters) {
                    if (filter.isFiltered(descriptor.name)) {
                        descriptor.included = false;
                        break;
                    }
                }
            }
        }
    }

    void includeServiceConfiguration(File artifact, String name) {
        ArtifactData data = artifacts.get(artifact);
        if (data != null) {
            ServiceConfiguration configuration = data.serviceConfigurations.get(name);
            if (configuration != null) {
                configuration.included = true;
            }
        }
    }

    void recordClass(File artifact, String outputName, byte[] bytecode) {
        Matcher matcher = VERSIONED_CLASS.matcher(outputName);
        int release = 0;
        String className;
        if (matcher.matches()) {
            release = Integer.parseInt(matcher.group(1));
            className = matcher.group(2);
        } else if (outputName.endsWith(".class")) {
            className = outputName.substring(0, outputName.length() - ".class".length());
        } else {
            return;
        }
        if ("module-info".equals(className)) {
            return;
        }
        if (release > 0 && !multiReleaseOutput) {
            return;
        }
        outputClasses.computeIfAbsent(release, key -> new LinkedHashSet<>()).add(className);
        ArtifactData origin = artifacts.get(artifact);
        int viewRelease = release == 0 ? FIRST_MODULE_RELEASE : release;
        if (origin != null && !artifact.equals(primaryArtifact) && origin.effectiveDescriptor(viewRelease) == null) {
            AutomaticModuleAnalyzer.Analysis analysis = AutomaticModuleAnalyzer.analyze(bytecode);
            retainedClasses
                    .computeIfAbsent(release, key -> new LinkedHashMap<>())
                    .put(
                            className,
                            new RetainedClass(
                                    origin,
                                    className,
                                    Math.max(
                                            release == 0 ? FIRST_MODULE_RELEASE : release,
                                            classRelease(analysis.classVersion)),
                                    analysis.references,
                                    analysis.serviceUses,
                                    analysis.unresolvedServiceUses));
        }
        int separator = className.lastIndexOf('/');
        if (separator < 0) {
            outputPackages
                    .computeIfAbsent(release, key -> new LinkedHashSet<>())
                    .add("");
        } else {
            String packaze = className.substring(0, separator).replace('/', '.');
            outputPackages
                    .computeIfAbsent(release, key -> new LinkedHashSet<>())
                    .add(packaze);
            artifactOutputPackages
                    .computeIfAbsent(artifact, key -> new TreeMap<>())
                    .computeIfAbsent(release, key -> new LinkedHashSet<>())
                    .add(packaze);
        }
    }

    void writeDescriptors(JarOutputStream output, ServicesResourceTransformer servicesTransformer)
            throws IOException, MojoExecutionException {
        if (primaryArtifact == null || !artifacts.containsKey(primaryArtifact)) {
            logger.warn("Module descriptor merging was requested, but the shaded output has no primary artifact.");
            return;
        }

        SortedSet<Integer> releases = collectReleaseBreakpoints();
        if (releases.isEmpty()) {
            logger.warn("Module descriptor merging was requested, but the primary artifact has no module-info.class.");
            return;
        }

        int earliestRelease = findEarliestPrimaryRelease(releases);
        if (earliestRelease < 0) {
            logger.warn("Module descriptor merging was requested, but the primary module descriptor was filtered.");
            return;
        }

        if (requiresPlatformAnalysis(releases)) {
            PlatformModuleIndex platform = platformModules();
            if (releases.last() > platform.getRelease()) {
                throw analysisJdkTooOld(releases.last(), platform.getRelease());
            }
            for (int release = earliestRelease; release <= platform.getRelease(); release++) {
                releases.add(release);
            }
        }

        List<MergedDescriptor> candidates = new ArrayList<>();
        for (int release : releases.tailSet(earliestRelease)) {
            DescriptorEntry primary = artifacts.get(primaryArtifact).effectiveDescriptor(release);
            if (primary != null && primary.included) {
                candidates.add(mergeRelease(release, primary));
            }
        }
        if (candidates.isEmpty()) {
            logger.warn("Module descriptor merging was requested, but the primary module descriptor was filtered.");
            return;
        }

        validatePrimaryBoundary(candidates);
        normalizeProvidersAndFloor(candidates, earliestRelease);
        normalizeInvariantRequirements(candidates);
        validateConfiguredRequirements(candidates);
        SortedSet<String> allPackages = collectAllPackages(candidates);
        if (allPackages.contains("")) {
            throw new MojoExecutionException("Cannot create module " + outputModuleName
                    + ": the shaded JAR contains a class in the unnamed package.");
        }
        for (MergedDescriptor descriptor : candidates) {
            descriptor.packages.clear();
            descriptor.packages.addAll(allPackages);
        }

        MergedDescriptor previous = null;
        boolean first = true;
        for (MergedDescriptor descriptor : candidates) {
            if (descriptor.release < modularFloor) {
                continue;
            }
            if (!first && descriptor.sameModuleSemantics(previous)) {
                continue;
            }
            String entryName = first && modularFloor == FIRST_MODULE_RELEASE && hasIncludedRootPrimaryDescriptor()
                    ? "module-info.class"
                    : "META-INF/versions/" + descriptor.release + "/module-info.class";
            writeDescriptor(output, entryName, descriptor);
            previous = descriptor;
            first = false;
        }

        bridgeAutomaticServices(candidates, earliestRelease, servicesTransformer);
        logRaisedFloor(earliestRelease);
    }

    boolean requiresMultiReleaseOutput() {
        return multiReleaseOutput;
    }

    void enableMultiReleaseOutput() {
        multiReleaseOutput = true;
    }

    private ArtifactData scanArtifact(File artifact) throws IOException, MojoExecutionException {
        return scanArtifact(artifact, true);
    }

    private ArtifactData scanArtifact(File artifact, boolean scanServices) throws IOException, MojoExecutionException {
        ArtifactData result = new ArtifactData(artifact);
        if (artifact.isDirectory()) {
            File manifestFile = new File(artifact, JarFile.MANIFEST_NAME);
            if (manifestFile.isFile()) {
                try (InputStream input = Files.newInputStream(manifestFile.toPath())) {
                    result.readManifest(new Manifest(input));
                }
            }
            scanDirectory(result, artifact, artifact, "", scanServices);
        } else {
            try (JarFile jar = new JarFile(artifact)) {
                result.readManifest(jar.getManifest());
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (isModuleInfo(entry.getName())) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            result.addDescriptor(
                                    entry.getName(),
                                    parseDescriptor(input, artifact, entry.getName()),
                                    entry.getTime());
                        }
                    } else if (entry.getName().endsWith(".class")) {
                        result.addClass(entry.getName());
                    } else if (scanServices && isServiceConfiguration(entry.getName())) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            result.addServiceConfiguration(parseServiceConfiguration(input, artifact, entry.getName()));
                        }
                    }
                }
            }
        }
        if (result.automaticModuleName == null) {
            result.automaticModuleName = deriveAutomaticModuleName(artifact.getName());
        }
        return result;
    }

    private void scanDirectory(ArtifactData data, File root, File current, String prefix, boolean scanServices)
            throws IOException, MojoExecutionException {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            String name = prefix + child.getName();
            if (child.isDirectory()) {
                scanDirectory(data, root, child, name + '/', scanServices);
            } else if (isModuleInfo(name)) {
                try (InputStream input = Files.newInputStream(child.toPath())) {
                    data.addDescriptor(name, parseDescriptor(input, root, name), child.lastModified());
                }
            } else if (name.endsWith(".class")) {
                data.addClass(name);
            } else if (scanServices && isServiceConfiguration(name)) {
                try (InputStream input = Files.newInputStream(child.toPath())) {
                    data.addServiceConfiguration(parseServiceConfiguration(input, root, name));
                }
            }
        }
    }

    private ServiceConfiguration parseServiceConfiguration(InputStream input, File artifact, String entry)
            throws IOException, MojoExecutionException {
        Matcher matcher = SERVICE_CONFIGURATION.matcher(entry);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(entry);
        }
        String service = matcher.group(1);
        validateServiceClassName(service, artifact, entry);
        SortedSet<String> providers = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (!provider.isEmpty()) {
                    validateServiceClassName(provider, artifact, entry);
                    providers.add(provider);
                }
            }
        }
        return new ServiceConfiguration(entry, service, providers);
    }

    private void validateServiceClassName(String name, File artifact, String entry) throws MojoExecutionException {
        if (!isBinaryClassName(name)) {
            throw new MojoExecutionException(
                    "Invalid service class name " + name + " in " + artifact + '!' + entry + '.');
        }
    }

    private static boolean isBinaryClassName(String name) {
        boolean start = true;
        for (int offset = 0; offset < name.length(); ) {
            int character = name.codePointAt(offset);
            if (character == '.') {
                if (start) {
                    return false;
                }
                start = true;
            } else if (start
                    ? !Character.isJavaIdentifierStart(character)
                    : !Character.isJavaIdentifierPart(character)) {
                return false;
            } else {
                start = false;
            }
            offset += Character.charCount(character);
        }
        return !start;
    }

    private ModuleDescriptorData parseDescriptor(InputStream input, File artifact, String entry)
            throws IOException, MojoExecutionException {
        try {
            final ModuleDescriptorData descriptor = new ModuleDescriptorData();
            ClassReader reader = new ClassReader(input);
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(
                                int version,
                                int access,
                                String name,
                                String signature,
                                String superName,
                                String[] interfaces) {
                            descriptor.classVersion = version;
                            descriptor.classAccess = access;
                        }

                        @Override
                        public ModuleVisitor visitModule(String name, int access, String version) {
                            descriptor.name = name;
                            descriptor.access = access;
                            descriptor.version = version;
                            return new ModuleVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMainClass(String mainClass) {
                                    descriptor.mainClass = mainClass;
                                }

                                @Override
                                public void visitPackage(String packaze) {
                                    descriptor.packages.add(packaze);
                                }

                                @Override
                                public void visitRequire(String module, int access, String version) {
                                    descriptor.requires.put(module, new Require(module, access, version));
                                }

                                @Override
                                public void visitExport(String packaze, int access, String... modules) {
                                    descriptor.exports.put(packaze, new Export(packaze, access, asSortedSet(modules)));
                                }

                                @Override
                                public void visitOpen(String packaze, int access, String... modules) {
                                    descriptor.opens.put(packaze, new Open(packaze, access, asSortedSet(modules)));
                                }

                                @Override
                                public void visitUse(String service) {
                                    descriptor.uses.add(service);
                                }

                                @Override
                                public void visitProvide(String service, String... providers) {
                                    descriptor
                                            .provides
                                            .computeIfAbsent(service, key -> new TreeSet<>())
                                            .addAll(Arrays.asList(providers));
                                }
                            };
                        }

                        @Override
                        public void visitAttribute(Attribute attribute) {
                            if (attribute instanceof ModuleTargetAttribute) {
                                descriptor.targetPlatform = ((ModuleTargetAttribute) attribute).targetPlatform;
                            } else if (attribute instanceof ModuleResolutionAttribute) {
                                descriptor.resolutionFlags = ((ModuleResolutionAttribute) attribute).resolutionFlags;
                            } else {
                                descriptor.attributes.add(attribute.type);
                            }
                        }
                    },
                    new Attribute[] {new ModuleTargetAttribute(), new ModuleResolutionAttribute()},
                    0);
            if (descriptor.name == null) {
                throw new MojoExecutionException(
                        "Invalid module descriptor " + entry + " in " + artifact + ": missing Module attribute.");
            }
            return descriptor;
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Cannot parse module descriptor " + entry + " in " + artifact, e);
        }
    }

    private MergedDescriptor mergeRelease(int release, DescriptorEntry primaryEntry) throws MojoExecutionException {
        ModuleDescriptorData primary = primaryEntry.descriptor;
        MergedDescriptor result = new MergedDescriptor(release, primaryEntry);
        result.name = outputModuleName;
        result.access = primary.access;
        result.version = primary.version;
        result.classVersion = primary.classVersion;
        result.classAccess = primary.classAccess;
        result.mainClass = relocateClass(primary.mainClass);
        result.targetPlatform = primary.targetPlatform;
        result.resolutionFlags = primary.resolutionFlags;
        warnDroppedAttributes(primary, new Origin(primaryArtifact, primaryEntry));

        Set<String> embeddedNames = new HashSet<>();
        for (ArtifactData artifact : artifacts.values()) {
            if (!artifact.file.equals(primaryArtifact)) {
                embeddedNames.add(artifact.configuredModuleName(release));
            }
        }
        embeddedNames.remove(null);
        embeddedNames.add(primaryModuleName);
        embeddedNames.add(outputModuleName);

        for (Export export : primary.exports.values()) {
            String packaze = relocatePackage(export.packaze);
            SortedSet<String> targets = removeEmbeddedTargets(export.targets, embeddedNames);
            if (export.targets == null || !targets.isEmpty()) {
                result.exports.put(packaze, new Export(packaze, export.access, targetsOrNull(export.targets, targets)));
            }
        }
        for (Open open : primary.opens.values()) {
            String packaze = relocatePackage(open.packaze);
            SortedSet<String> targets = removeEmbeddedTargets(open.targets, embeddedNames);
            if (open.targets == null || !targets.isEmpty()) {
                result.opens.put(packaze, new Open(packaze, open.access, targetsOrNull(open.targets, targets)));
            }
        }
        result.primaryExports.putAll(result.exports);
        result.primaryOpens.putAll(result.opens);

        addRequirements(
                result, primary.requires.values(), embeddedNames, new Origin(primaryArtifact, primaryEntry), true);
        addServices(result, primary, true, new Origin(primaryArtifact, primaryEntry));

        for (ArtifactData artifact : artifacts.values()) {
            if (artifact.file.equals(primaryArtifact)) {
                continue;
            }
            DescriptorEntry descriptor = artifact.effectiveDescriptor(release);
            if (descriptor == null) {
                addAutomaticServices(result, artifact, release);
                addAutomaticBoundary(result, artifact, release);
                continue;
            }
            if (!descriptor.included) {
                continue;
            }
            warnDroppedAttributes(descriptor.descriptor, new Origin(artifact.file, descriptor));
            addRequirements(
                    result,
                    descriptor.descriptor.requires.values(),
                    embeddedNames,
                    new Origin(artifact.file, descriptor),
                    false);
            addServices(result, descriptor.descriptor, false, new Origin(artifact.file, descriptor));
            addEmbeddedBoundary(result, artifact, descriptor.descriptor, embeddedNames, release);
        }

        addAutomaticRequirements(result, release);
        addConfiguredDirectives(result, embeddedNames, release);
        result.packages.addAll(effectivePackages(release));
        validateProjectDirectives(result, primary, release);
        if ((result.access & Opcodes.ACC_OPEN) != 0) {
            result.opens.clear();
        }
        return result;
    }

    private void addAutomaticServices(MergedDescriptor target, ArtifactData artifact, int release) {
        if (artifact.effectiveDescriptor(release) != null) {
            return;
        }
        for (ServiceConfiguration configuration : artifact.serviceConfigurations.values()) {
            if (!configuration.included) {
                continue;
            }
            String service = relocateClass(configuration.service);
            for (String provider : configuration.providers) {
                String relocatedProvider = relocateClass(provider);
                if (hasClass(relocatedProvider, release)) {
                    target.provides
                            .computeIfAbsent(service, key -> new TreeSet<>())
                            .add(relocatedProvider);
                    providerOrigins.putIfAbsent(
                            new ServiceProvider(service, relocatedProvider),
                            new Origin(artifact.file, configuration.name));
                } else {
                    logger.warn("Omitting automatic-module provider " + relocatedProvider + " from "
                            + artifact.file + '!' + configuration.name
                            + " because the class is not present in the shaded output.");
                }
            }
        }
    }

    private void addAutomaticBoundary(MergedDescriptor target, ArtifactData artifact, int release) {
        if (configuration.getPublicBoundary() != ModuleInfoPublicBoundary.MERGE) {
            return;
        }
        for (String packaze : effectiveArtifactPackages(artifact.file, release)) {
            mergeExport(target.exports, new Export(packaze, 0, null));
            mergeOpen(target.opens, new Open(packaze, 0, null));
        }
    }

    private void addEmbeddedBoundary(
            MergedDescriptor target,
            ArtifactData artifact,
            ModuleDescriptorData descriptor,
            Set<String> embeddedNames,
            int release) {
        if (configuration.getPublicBoundary() != ModuleInfoPublicBoundary.MERGE) {
            return;
        }
        Set<String> packages = effectivePackages(release);
        for (Export export : descriptor.exports.values()) {
            String packaze = relocatePackage(export.packaze);
            if (!packages.contains(packaze)) {
                logger.warn("Omitting embedded export " + packaze
                        + " because the package is not present in the shaded output at Java " + release + '.');
                continue;
            }
            SortedSet<String> targets = removeEmbeddedTargets(export.targets, embeddedNames);
            if (export.targets == null || !targets.isEmpty()) {
                mergeExport(target.exports, new Export(packaze, 0, targetsOrNull(export.targets, targets)));
            }
        }
        if ((descriptor.access & Opcodes.ACC_OPEN) != 0) {
            for (String packaze : effectiveArtifactPackages(artifact.file, release)) {
                mergeOpen(target.opens, new Open(packaze, 0, null));
            }
        } else {
            for (Open open : descriptor.opens.values()) {
                String packaze = relocatePackage(open.packaze);
                if (!packages.contains(packaze)) {
                    logger.warn("Omitting embedded open package " + packaze
                            + " because the package is not present in the shaded output at Java " + release + '.');
                    continue;
                }
                SortedSet<String> targets = removeEmbeddedTargets(open.targets, embeddedNames);
                if (open.targets == null || !targets.isEmpty()) {
                    mergeOpen(target.opens, new Open(packaze, 0, targetsOrNull(open.targets, targets)));
                }
            }
        }
    }

    private void addConfiguredDirectives(MergedDescriptor target, Set<String> embeddedNames, int release)
            throws MojoExecutionException {
        for (ModuleInfoConfiguration.PackageDirective configured : configuration.getAdditionalExports()) {
            Export export = configuredExport(configured, embeddedNames, "export");
            if (export != null) {
                mergeExport(target.exports, export);
            }
        }
        for (ModuleInfoConfiguration.PackageDirective configured : configuration.getAdditionalOpens()) {
            Open open = configuredOpen(configured, embeddedNames);
            if (open != null) {
                mergeOpen(target.opens, open);
            }
        }
        for (ModuleInfoConfiguration.Requirement configured : configuration.getAdditionalRequires()) {
            String module = configured.getModule();
            if (module == null || module.trim().isEmpty()) {
                throw new MojoExecutionException(
                        "moduleInfo.additionalRequires contains a requirement with no module.");
            }
            if (embeddedNames.contains(module)) {
                throw new MojoExecutionException("moduleInfo.additionalRequires cannot require embedded module "
                        + module + " in the amalgamated output.");
            }
            if (isPlatformModule(module) && !platformModules().hasModule(module, release)) {
                continue;
            }
            int access = 0;
            if (configured.isStaticRequirement()) {
                access |= Opcodes.ACC_STATIC_PHASE;
            }
            if (configured.isTransitive()) {
                access |= Opcodes.ACC_TRANSITIVE;
            }
            target.requires.merge(module, new Require(module, access, null), Require::strongest);
        }
        for (String service : configuration.getAdditionalUses()) {
            addConfiguredUse(target, service, "moduleInfo.additionalUses", release);
        }
        for (String service : configuration.getDynamicUses()) {
            addConfiguredUse(target, service, "moduleInfo.dynamicUses", release);
        }
    }

    private void addConfiguredUse(MergedDescriptor target, String configured, String parameter, int release)
            throws MojoExecutionException {
        String service = relocateConfiguredClass(configured, parameter);
        if (hasClass(service, release)) {
            target.uses.add(service);
            return;
        }
        SortedSet<String> owners = findModuleOwners(toInternalName(service), release);
        if (owners.size() > 1) {
            throw new MojoExecutionException(
                    "Configured service type " + service + " is owned by multiple modules " + owners + '.');
        }
        if (!owners.isEmpty()) {
            target.uses.add(service);
            String owner = owners.first();
            if (!"java.base".equals(owner) && !isAmalgamatedModuleName(owner)) {
                target.requires.merge(owner, new Require(owner, 0, null), Require::strongest);
            }
        }
    }

    private Export configuredExport(
            ModuleInfoConfiguration.PackageDirective configured, Set<String> embeddedNames, String directive)
            throws MojoExecutionException {
        String packaze = configuredPackage(configured, directive);
        SortedSet<String> targets = configuredTargets(configured, embeddedNames);
        return configured.getTargets().isEmpty() || !targets.isEmpty()
                ? new Export(packaze, 0, configured.getTargets().isEmpty() ? null : targets)
                : null;
    }

    private Open configuredOpen(ModuleInfoConfiguration.PackageDirective configured, Set<String> embeddedNames)
            throws MojoExecutionException {
        String packaze = configuredPackage(configured, "open");
        SortedSet<String> targets = configuredTargets(configured, embeddedNames);
        return configured.getTargets().isEmpty() || !targets.isEmpty()
                ? new Open(packaze, 0, configured.getTargets().isEmpty() ? null : targets)
                : null;
    }

    private String configuredPackage(ModuleInfoConfiguration.PackageDirective configured, String directive)
            throws MojoExecutionException {
        if (configured.getPackageName() == null
                || configured.getPackageName().trim().isEmpty()) {
            throw new MojoExecutionException("moduleInfo.additional" + directive + "s contains an empty package.");
        }
        return relocatePackage(configured.getPackageName());
    }

    private SortedSet<String> configuredTargets(
            ModuleInfoConfiguration.PackageDirective configured, Set<String> embeddedNames) {
        SortedSet<String> targets = new TreeSet<>(configured.getTargets());
        targets.removeAll(embeddedNames);
        return targets;
    }

    private String relocateConfiguredClass(String className, String parameter) throws MojoExecutionException {
        if (className == null || className.trim().isEmpty()) {
            throw new MojoExecutionException(parameter + " contains an empty service class.");
        }
        return relocateClass(className);
    }

    private boolean requiresPlatformAnalysis(SortedSet<Integer> releases) {
        for (int release : releases) {
            for (RetainedClass retainedClass : effectiveRetainedClasses(release).values()) {
                if (retainedClass.origin.effectiveDescriptor(release) == null) {
                    return true;
                }
            }
        }
        for (ModuleInfoConfiguration.Requirement requirement : configuration.getAdditionalRequires()) {
            if (requirement.getModule() != null && isPlatformModule(requirement.getModule())) {
                return true;
            }
        }
        return !configuration.getAdditionalUses().isEmpty()
                || !configuration.getDynamicUses().isEmpty();
    }

    private void validateConfiguredRequirements(List<MergedDescriptor> candidates) throws MojoExecutionException {
        for (ModuleInfoConfiguration.Requirement requirement : configuration.getAdditionalRequires()) {
            String module = requirement.getModule();
            boolean found = false;
            for (MergedDescriptor candidate : candidates) {
                if (candidate.requires.containsKey(module)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new MojoExecutionException("Configured moduleInfo requirement " + module
                        + " is not available through Java " + candidates.get(candidates.size() - 1).release
                        + " in the selected analysis JDK or resolved dependency hull.");
            }
        }
        validateConfiguredUses(candidates, configuration.getAdditionalUses(), "moduleInfo.additionalUses");
        validateConfiguredUses(candidates, configuration.getDynamicUses(), "moduleInfo.dynamicUses");
    }

    private void validateConfiguredUses(List<MergedDescriptor> candidates, Set<String> configured, String parameter)
            throws MojoExecutionException {
        for (String service : configured) {
            String relocated = relocateConfiguredClass(service, parameter);
            boolean found = false;
            for (MergedDescriptor candidate : candidates) {
                if (candidate.uses.contains(relocated)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new MojoExecutionException("Configured service type " + relocated
                        + " is not available through Java " + candidates.get(candidates.size() - 1).release
                        + " in the shaded output, selected analysis JDK, or resolved dependency hull.");
            }
        }
    }

    private static void mergeExport(Map<String, Export> exports, Export addition) {
        Export existing = exports.get(addition.packaze);
        if (existing == null) {
            exports.put(addition.packaze, addition);
        } else {
            exports.put(
                    addition.packaze,
                    new Export(
                            addition.packaze,
                            existing.access | addition.access,
                            mergeDirectiveTargets(existing.targets, addition.targets)));
        }
    }

    private static void mergeOpen(Map<String, Open> opens, Open addition) {
        Open existing = opens.get(addition.packaze);
        if (existing == null) {
            opens.put(addition.packaze, addition);
        } else {
            opens.put(
                    addition.packaze,
                    new Open(
                            addition.packaze,
                            existing.access | addition.access,
                            mergeDirectiveTargets(existing.targets, addition.targets)));
        }
    }

    private static SortedSet<String> mergeDirectiveTargets(SortedSet<String> existing, SortedSet<String> addition) {
        if (existing == null || addition == null) {
            return null;
        }
        SortedSet<String> targets = new TreeSet<>(existing);
        targets.addAll(addition);
        return targets;
    }

    private void addRequirements(
            MergedDescriptor target,
            Collection<Require> requirements,
            Set<String> embeddedNames,
            Origin origin,
            boolean primary) {
        for (Require requirement : requirements) {
            if (embeddedNames.contains(requirement.module)) {
                continue;
            }
            Require retained = primary
                    ? requirement
                    : new Require(
                            requirement.module, requirement.access & Opcodes.ACC_STATIC_PHASE, requirement.version);
            target.requires.merge(retained.module, retained, Require::strongest);
            if (retained.isTransitive()) {
                transitiveRequirementOrigins.putIfAbsent(requirement.module, origin);
            }
        }
    }

    private void addAutomaticRequirements(MergedDescriptor target, int release) throws MojoExecutionException {
        for (RetainedClass retainedClass : effectiveRetainedClasses(release).values()) {
            if (retainedClass.origin.effectiveDescriptor(release) != null) {
                continue;
            }

            PlatformModuleIndex platform = platformModules();
            if (retainedClass.effectiveRelease > platform.getRelease()) {
                throw analysisJdkTooOld(retainedClass.effectiveRelease, platform.getRelease());
            }
            if (warnedAutomaticAnalysis.add(retainedClass.origin.file)) {
                logger.warn("Inferring module requirements from bytecode retained from automatic module "
                        + retainedClass.origin.effectiveOutputModuleName(release) + " in " + retainedClass.origin.file
                        + "; reflective and string-only dependencies cannot be inferred.");
            }

            if (!retainedClass.unresolvedServiceUses.isEmpty()) {
                if (configuration.getDynamicUses().isEmpty()) {
                    throw new MojoExecutionException("Cannot preserve ServiceLoader semantics for automatic module "
                            + retainedClass.origin.effectiveOutputModuleName(release) + " in "
                            + retainedClass.origin.file
                            + ": retained class " + toClassName(retainedClass.className) + " invokes "
                            + retainedClass.unresolvedServiceUses.first()
                            + ", but its service type cannot be determined from bytecode. List every possible service "
                            + "type in moduleInfo.dynamicUses.");
                }
                if (warnedDynamicUses.add(retainedClass.origin.file)) {
                    logger.warn("Using moduleInfo.dynamicUses as the complete service-type set for dynamic "
                            + "ServiceLoader calls retained from " + retainedClass.origin.file + '.');
                }
            }
            for (String service : retainedClass.serviceUses) {
                target.uses.add(toClassName(service));
            }

            for (String reference : retainedClass.references) {
                if (hasClass(reference, release)) {
                    continue;
                }
                SortedSet<String> owners = findModuleOwners(reference, release);
                if (owners.isEmpty()) {
                    throw new MojoExecutionException("Cannot infer module requirements for automatic module "
                            + retainedClass.origin.effectiveOutputModuleName(release) + " in "
                            + retainedClass.origin.file
                            + ": retained class " + toClassName(retainedClass.className) + " references "
                            + toClassName(reference)
                            + ", which is not present in the shaded output, any resolved dependency, or the "
                            + "Java " + release + " platform represented by " + effectiveAnalysisJdkHome()
                            + '.');
                }
                if (owners.size() > 1) {
                    throw new MojoExecutionException("Cannot infer module requirements for automatic module "
                            + retainedClass.origin.effectiveOutputModuleName(release) + " in "
                            + retainedClass.origin.file
                            + ": retained class " + toClassName(retainedClass.className) + " references "
                            + toClassName(reference) + ", which is owned by multiple modules " + owners + '.');
                }
                String module = owners.first();
                if ("java.base".equals(module) || isAmalgamatedModuleName(module)) {
                    continue;
                }
                target.requires.merge(module, new Require(module, 0, null), Require::strongest);
                logger.debug("Inferred requirement on " + module + " from automatic-module reference "
                        + toClassName(retainedClass.className) + " -> " + toClassName(reference));
            }
        }
    }

    private Map<String, RetainedClass> effectiveRetainedClasses(int release) {
        Map<String, RetainedClass> result = new LinkedHashMap<>();
        Map<String, RetainedClass> root = retainedClasses.get(0);
        if (root != null) {
            result.putAll(root);
        }
        for (Map.Entry<Integer, Map<String, RetainedClass>> entry : retainedClasses.entrySet()) {
            if (entry.getKey() > 0 && entry.getKey() <= release) {
                result.putAll(entry.getValue());
            }
        }
        return result;
    }

    private SortedSet<String> findModuleOwners(String className, int release) throws MojoExecutionException {
        SortedSet<String> owners = platformModules().findOwners(className, release);
        for (ArtifactData artifact : dependencyArtifacts.values()) {
            if (artifact.hasClass(className, release)) {
                String moduleName = artifact.configuredModuleName(release);
                if (moduleName != null) {
                    owners.add(moduleName);
                }
            }
        }
        return owners;
    }

    private PlatformModuleIndex platformModules() throws MojoExecutionException {
        if (platformModules == null) {
            platformModules = new PlatformModuleIndex(effectiveAnalysisJdkHome());
        }
        return platformModules;
    }

    private File effectiveAnalysisJdkHome() {
        return analysisJdkHome == null ? new File(System.getProperty("java.home")) : analysisJdkHome;
    }

    private MojoExecutionException analysisJdkTooOld(int requiredRelease, int actualRelease) {
        return new MojoExecutionException("Module-info analysis requires Java " + requiredRelease
                + " platform data, but the selected JDK at " + effectiveAnalysisJdkHome() + " provides Java "
                + actualRelease + ". Configure moduleInfo.analysisJdkToolchain with Java " + requiredRelease
                + " or newer.");
    }

    private static String toClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    private void addServices(MergedDescriptor target, ModuleDescriptorData source, boolean project, Origin origin) {
        for (String service : source.uses) {
            target.uses.add(relocateClass(service));
        }
        for (Map.Entry<String, SortedSet<String>> entry : source.provides.entrySet()) {
            String service = relocateClass(entry.getKey());
            for (String provider : entry.getValue()) {
                String relocatedProvider = relocateClass(provider);
                if (project || hasClass(relocatedProvider, target.release)) {
                    target.provides
                            .computeIfAbsent(service, key -> new TreeSet<>())
                            .add(relocatedProvider);
                    providerOrigins.putIfAbsent(new ServiceProvider(service, relocatedProvider), origin);
                } else {
                    logger.warn("Omitting module provider " + relocatedProvider + " from " + origin.artifact
                            + " because the class is not present in the shaded output.");
                }
            }
        }
    }

    private void validateProjectDirectives(MergedDescriptor result, ModuleDescriptorData primary, int release)
            throws MojoExecutionException {
        Set<String> packages = effectivePackages(release);
        for (String packaze : result.primaryExports.keySet()) {
            if (!packages.contains(packaze)) {
                throw new MojoExecutionException("Cannot create module " + result.name + ": exported package " + packaze
                        + " from the primary descriptor is absent from the shaded output at Java " + release + '.');
            }
        }
        for (String packaze : result.primaryOpens.keySet()) {
            if (!packages.contains(packaze)) {
                throw new MojoExecutionException("Cannot create module " + result.name + ": opened package " + packaze
                        + " from the primary descriptor is absent from the shaded output at Java " + release + '.');
            }
        }
        if (result.mainClass != null && !hasClass(result.mainClass, release)) {
            throw new MojoExecutionException("Cannot create module " + result.name + ": main class " + result.mainClass
                    + " from the primary descriptor is absent from the shaded output at Java " + release + '.');
        }
        for (Map.Entry<String, SortedSet<String>> provides : primary.provides.entrySet()) {
            for (String provider : provides.getValue()) {
                String relocated = relocateClass(provider);
                if (!hasClass(relocated, release)) {
                    throw new MojoExecutionException("Cannot create module " + result.name + ": provider " + relocated
                            + " from the primary descriptor is absent from the shaded output at Java " + release + '.');
                }
            }
        }
    }

    private void validatePrimaryBoundary(List<MergedDescriptor> candidates) throws MojoExecutionException {
        MergedDescriptor first = candidates.get(0);
        for (MergedDescriptor candidate : candidates) {
            if (!Objects.equals(first.name, candidate.name)
                    || first.access != candidate.access
                    || !Objects.equals(first.version, candidate.version)
                    || !Objects.equals(first.mainClass, candidate.mainClass)
                    || !Objects.equals(first.targetPlatform, candidate.targetPlatform)
                    || !Objects.equals(first.resolutionFlags, candidate.resolutionFlags)
                    || !Objects.equals(first.primaryExports, candidate.primaryExports)
                    || !Objects.equals(first.primaryOpens, candidate.primaryOpens)) {
                throw new MojoExecutionException(
                        "Primary module descriptors for " + primaryArtifact
                                + " change module identity, exports, opens, main class, target, or resolution across releases.");
            }
        }
    }

    private void normalizeInvariantRequirements(List<MergedDescriptor> candidates) {
        Map<String, Require> invariant = new TreeMap<>();
        for (MergedDescriptor candidate : candidates) {
            for (Require requirement : candidate.requires.values()) {
                if (!isPlatformModule(requirement.module) || requirement.isTransitive()) {
                    invariant.merge(requirement.module, requirement, Require::strongest);
                }
            }
        }
        for (MergedDescriptor candidate : candidates) {
            for (String module : invariant.keySet()) {
                candidate.requires.remove(module);
            }
            candidate.requires.putAll(invariant);
        }
    }

    private void normalizeProvidersAndFloor(List<MergedDescriptor> candidates, int earliestRelease)
            throws MojoExecutionException {
        Map<String, SortedSet<String>> invariantProvides = new TreeMap<>();
        Map<String, Export> invariantExports = new TreeMap<>();
        Map<String, Open> invariantOpens = new TreeMap<>();
        for (MergedDescriptor candidate : candidates) {
            mergeProvides(invariantProvides, candidate.provides);
            for (Export export : candidate.exports.values()) {
                mergeExport(invariantExports, export);
            }
            for (Open open : candidate.opens.values()) {
                mergeOpen(invariantOpens, open);
            }
        }

        int floor = earliestRelease;
        List<FloorReason> reasons = new ArrayList<>();
        Map<ServiceProvider, Integer> providerAvailability = new HashMap<>();
        for (Map.Entry<String, SortedSet<String>> provides : invariantProvides.entrySet()) {
            for (String provider : provides.getValue()) {
                int available = firstAvailableRelease(provider, candidates);
                if (available < 0) {
                    throw new MojoExecutionException("Cannot create module " + outputModuleName + ": provider class "
                            + provider + " is absent.");
                }
                if (available > earliestRelease) {
                    ServiceProvider key = new ServiceProvider(provides.getKey(), provider);
                    reasons.add(FloorReason.provider(key, available, providerOrigins.get(key)));
                }
                providerAvailability.put(new ServiceProvider(provides.getKey(), provider), available);
                floor = Math.max(floor, available);
            }
        }

        for (String packaze : invariantExports.keySet()) {
            int available = firstAvailablePackageRelease(packaze, candidates);
            if (available < 0) {
                throw new MojoExecutionException(
                        "Cannot export package " + packaze + " from " + outputModuleName + ": the package is absent.");
            }
            if (available > earliestRelease) {
                reasons.add(FloorReason.exportedPackage(packaze, available));
                floor = Math.max(floor, available);
            }
        }
        for (String packaze : invariantOpens.keySet()) {
            int available = firstAvailablePackageRelease(packaze, candidates);
            if (available < 0) {
                throw new MojoExecutionException(
                        "Cannot open package " + packaze + " from " + outputModuleName + ": the package is absent.");
            }
            if (available > earliestRelease) {
                reasons.add(FloorReason.openPackage(packaze, available));
                floor = Math.max(floor, available);
            }
        }

        Map<String, Integer> platformTransitiveStart = new TreeMap<>();
        for (MergedDescriptor candidate : candidates) {
            for (Require requirement : candidate.requires.values()) {
                if (isPlatformModule(requirement.module) && requirement.isTransitive()) {
                    platformTransitiveStart.putIfAbsent(requirement.module, candidate.release);
                }
            }
        }
        for (Map.Entry<String, Integer> requirement : platformTransitiveStart.entrySet()) {
            if (requirement.getValue() > earliestRelease) {
                reasons.add(FloorReason.requirement(
                        requirement.getKey(),
                        requirement.getValue(),
                        transitiveRequirementOrigins.get(requirement.getKey())));
            }
            floor = Math.max(floor, requirement.getValue());
        }

        for (Map.Entry<ServiceProvider, Integer> provider : providerAvailability.entrySet()) {
            if (provider.getValue() < floor && !hasRootClass(provider.getKey().provider)) {
                throw new MojoExecutionException("Provider " + provider.getKey().provider + " for "
                        + provider.getKey().service + " first available in Java " + provider.getValue()
                        + " cannot be exposed while " + outputModuleName + " is automatic below its Java " + floor
                        + " modular floor: META-INF/services cannot be versioned.");
            }
        }

        this.modularFloor = floor;
        this.floorReasons = reasons;
        for (MergedDescriptor candidate : candidates) {
            candidate.provides.clear();
            mergeProvides(candidate.provides, invariantProvides);
            candidate.exports.clear();
            candidate.exports.putAll(invariantExports);
            candidate.opens.clear();
            candidate.opens.putAll(invariantOpens);
        }
    }

    private void bridgeAutomaticServices(
            List<MergedDescriptor> candidates, int earliestRelease, ServicesResourceTransformer servicesTransformer) {
        if (servicesTransformer == null || modularFloor <= earliestRelease) {
            return;
        }
        MergedDescriptor floorDescriptor = null;
        for (MergedDescriptor candidate : candidates) {
            if (candidate.release >= modularFloor) {
                floorDescriptor = candidate;
                break;
            }
        }
        if (floorDescriptor == null) {
            return;
        }
        for (Map.Entry<String, SortedSet<String>> provides : floorDescriptor.provides.entrySet()) {
            for (String provider : provides.getValue()) {
                if (hasRootClass(provider)) {
                    servicesTransformer.addServiceProvider(provides.getKey(), provider, floorDescriptor.timestamp);
                }
            }
        }
    }

    private void logRaisedFloor(int earliestRelease) {
        if (modularFloor <= earliestRelease) {
            return;
        }
        logger.warn("Raising the module descriptor floor for " + outputModuleName + " from Java " + earliestRelease
                + " to Java " + modularFloor + '.');
        logger.warn("No explicit module descriptor will be effective on Java " + earliestRelease + " through "
                + (modularFloor - 1) + "; the JAR will be the automatic module " + outputModuleName
                + " on those releases.");
        logger.warn("Automatic-Module-Name has been set to keep the module name stable.");
        logger.warn("Reasons:");
        List<FloorReason> reasons = new ArrayList<>(floorReasons);
        Collections.sort(reasons);
        for (FloorReason reason : reasons) {
            logger.warn("  - " + reason.describe());
        }
    }

    private void writeDescriptor(JarOutputStream output, String entryName, MergedDescriptor descriptor)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                descriptor.classVersion, descriptor.classAccess | Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor module = writer.visitModule(descriptor.name, descriptor.access, descriptor.version);
        if (descriptor.mainClass != null) {
            module.visitMainClass(toInternalName(descriptor.mainClass));
        }
        for (String packaze : descriptor.packages) {
            module.visitPackage(toInternalName(packaze));
        }
        for (Require requirement : descriptor.requires.values()) {
            module.visitRequire(requirement.module, requirement.access, requirement.version);
        }
        for (Export export : descriptor.exports.values()) {
            module.visitExport(toInternalName(export.packaze), export.access, toArrayOrNull(export.targets));
        }
        for (Open open : descriptor.opens.values()) {
            module.visitOpen(toInternalName(open.packaze), open.access, toArrayOrNull(open.targets));
        }
        for (String use : descriptor.uses) {
            module.visitUse(toInternalName(use));
        }
        for (Map.Entry<String, SortedSet<String>> provides : descriptor.provides.entrySet()) {
            String[] providers = provides.getValue().stream()
                    .map(ModuleInfoProcessor::toInternalName)
                    .toArray(String[]::new);
            module.visitProvide(toInternalName(provides.getKey()), providers);
        }
        module.visitEnd();
        if (descriptor.targetPlatform != null) {
            writer.visitAttribute(new ModuleTargetAttribute(descriptor.targetPlatform));
        }
        if (descriptor.resolutionFlags != null) {
            writer.visitAttribute(new ModuleResolutionAttribute(descriptor.resolutionFlags));
        }
        writer.visitEnd();

        JarEntry entry = new JarEntry(entryName);
        entry.setTime(descriptor.timestamp);
        output.putNextEntry(entry);
        output.write(writer.toByteArray());
    }

    private SortedSet<Integer> collectReleaseBreakpoints() {
        SortedSet<Integer> releases = new TreeSet<>();
        for (ArtifactData artifact : artifacts.values()) {
            for (DescriptorEntry descriptor : artifact.descriptorsByName.values()) {
                if (descriptor.included && (descriptor.physicalRelease == 0 || artifact.multiRelease)) {
                    releases.add(descriptor.effectiveRelease());
                }
            }
        }
        for (Integer release : outputClasses.keySet()) {
            releases.add(release == 0 ? FIRST_MODULE_RELEASE : release);
        }
        releases.removeIf(release -> release < FIRST_MODULE_RELEASE);
        return releases;
    }

    private int findEarliestPrimaryRelease(SortedSet<Integer> releases) {
        ArtifactData primary = artifacts.get(primaryArtifact);
        for (int release : releases) {
            DescriptorEntry descriptor = primary.effectiveDescriptor(release);
            if (descriptor != null && descriptor.included) {
                return release;
            }
        }
        return -1;
    }

    private boolean hasIncludedRootPrimaryDescriptor() {
        ArtifactData primary = artifacts.get(primaryArtifact);
        DescriptorEntry root = primary.descriptorsByName.get("module-info.class");
        return root != null && root.included;
    }

    private SortedSet<String> collectAllPackages(List<MergedDescriptor> candidates) {
        SortedSet<String> packages = new TreeSet<>();
        for (MergedDescriptor candidate : candidates) {
            if (candidate.release >= modularFloor) {
                packages.addAll(candidate.packages);
            }
        }
        return packages;
    }

    private Set<String> effectivePackages(int release) {
        return effectivePackages(outputPackages, release);
    }

    private Set<String> effectiveArtifactPackages(File artifact, int release) {
        return effectivePackages(
                artifactOutputPackages.getOrDefault(artifact, Collections.<Integer, Set<String>>emptyMap()), release);
    }

    private Set<String> effectivePackages(Map<Integer, Set<String>> packages, int release) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> root = packages.get(0);
        if (root != null) {
            result.addAll(root);
        }
        for (Map.Entry<Integer, Set<String>> entry : packages.entrySet()) {
            if (entry.getKey() > 0 && entry.getKey() <= release) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    private boolean hasClass(String className, int release) {
        String internalName = toInternalName(className);
        if (outputClasses.getOrDefault(0, Collections.emptySet()).contains(internalName)) {
            return true;
        }
        for (Map.Entry<Integer, Set<String>> entry : outputClasses.entrySet()) {
            if (entry.getKey() > 0
                    && entry.getKey() <= release
                    && entry.getValue().contains(internalName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRootClass(String className) {
        return outputClasses.getOrDefault(0, Collections.emptySet()).contains(toInternalName(className));
    }

    private int firstAvailableRelease(String className, List<MergedDescriptor> candidates) {
        for (MergedDescriptor candidate : candidates) {
            if (hasClass(className, candidate.release)) {
                return candidate.release;
            }
        }
        return -1;
    }

    private int firstAvailablePackageRelease(String packaze, List<MergedDescriptor> candidates) {
        for (MergedDescriptor candidate : candidates) {
            if (effectivePackages(candidate.release).contains(packaze)) {
                return candidate.release;
            }
        }
        return -1;
    }

    private String relocatePackage(String packaze) {
        return relocateEntity(packaze);
    }

    private String relocateClass(String clazz) {
        return relocateEntity(clazz);
    }

    private String relocateEntity(String name) {
        if (name == null) {
            return null;
        }
        String dotted = name.replace('/', '.');
        String path = name.replace('.', '/');
        for (Relocator relocator : relocators) {
            if (relocator.canRelocateClass(dotted)) {
                return relocator.relocateClass(dotted).replace('/', '.');
            } else if (relocator.canRelocatePath(path)) {
                return relocator.relocatePath(path).replace('/', '.');
            }
        }
        return dotted;
    }

    private void determinePrimaryModuleName() throws MojoExecutionException {
        if (primaryArtifact == null) {
            return;
        }
        ArtifactData primary = artifacts.get(primaryArtifact);
        if (primary == null || primary.descriptorsByName.isEmpty()) {
            return;
        }
        Set<String> names = new TreeSet<>();
        for (DescriptorEntry descriptor : primary.descriptorsByName.values()) {
            if (descriptor.physicalRelease == 0 || primary.multiRelease) {
                names.add(descriptor.descriptor.name);
            }
        }
        if (names.isEmpty()) {
            return;
        }
        if (names.size() != 1) {
            throw new MojoExecutionException(
                    "Primary artifact " + primaryArtifact + " has conflicting module names " + names + '.');
        }
        primaryModuleName = names.iterator().next();
        if (primary.automaticModuleNameExplicit
                && primary.automaticModuleName != null
                && !primaryModuleName.equals(primary.automaticModuleName)) {
            throw new MojoExecutionException("Primary artifact " + primaryArtifact + " declares Automatic-Module-Name "
                    + primary.automaticModuleName + " but its descriptor declares " + primaryModuleName + '.');
        }
    }

    private void determineOutputModuleName() throws MojoExecutionException {
        if (primaryModuleName == null) {
            return;
        }
        String configuredModuleName = configuration.getModuleName();
        if (configuredModuleName == null) {
            outputModuleName = primaryModuleName;
        } else if (!ModuleInfoConfiguration.isValidModuleName(configuredModuleName)) {
            throw new MojoExecutionException(
                    "Invalid moduleInfo.moduleName '" + configuredModuleName + "': expected a qualified Java name.");
        } else {
            outputModuleName = configuredModuleName;
        }
    }

    private boolean isAmalgamatedModuleName(String moduleName) {
        return primaryModuleName.equals(moduleName) || outputModuleName.equals(moduleName);
    }

    private boolean primaryRootDescriptorRequiresVersionedOutput() {
        if (primaryArtifact == null) {
            return false;
        }
        ArtifactData primary = artifacts.get(primaryArtifact);
        if (primary == null) {
            return false;
        }
        DescriptorEntry root = primary.descriptorsByName.get("module-info.class");
        return root != null && root.effectiveRelease() > FIRST_MODULE_RELEASE;
    }

    private void warnDroppedAttributes(ModuleDescriptorData descriptor, Origin origin) {
        for (String attribute : descriptor.attributes) {
            String warningKey = origin.describe() + ':' + attribute;
            if (!warnedDroppedAttributes.add(warningKey)) {
                continue;
            }
            if ("ModuleHashes".equals(attribute)) {
                logger.warn("Dropping ModuleHashes from " + origin.describe()
                        + " because shading changes the module contents and invalidates the recorded hashes.");
            } else {
                logger.warn("Dropping unsupported module descriptor attribute " + attribute + " from "
                        + origin.describe() + " because it cannot be safely rewritten.");
            }
        }
    }

    private static String deriveAutomaticModuleName(String filename) {
        String name = filename;
        if (name.toLowerCase().endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        Matcher version = Pattern.compile("-(\\d+(?:[.]|$))").matcher(name);
        if (version.find()) {
            name = name.substring(0, version.start());
        }
        return name.replaceAll("[^A-Za-z0-9]", ".").replaceAll("[.]+", ".").replaceAll("^[.]|[.]$", "");
    }

    private static boolean isPlatformModule(String module) {
        return module.startsWith("java.") || module.startsWith("jdk.");
    }

    private static int classRelease(int classVersion) {
        int major = classVersion & 0xFFFF;
        return Math.max(FIRST_MODULE_RELEASE, major - 44);
    }

    private static String toInternalName(String name) {
        return name == null ? null : name.replace('.', '/');
    }

    private static String[] toArrayOrNull(SortedSet<String> values) {
        return values == null ? null : values.toArray(new String[0]);
    }

    private static SortedSet<String> asSortedSet(String[] values) {
        return values == null ? null : new TreeSet<>(Arrays.asList(values));
    }

    private static SortedSet<String> removeEmbeddedTargets(SortedSet<String> targets, Set<String> embedded) {
        if (targets == null) {
            return new TreeSet<>();
        }
        SortedSet<String> result = new TreeSet<>(targets);
        result.removeAll(embedded);
        return result;
    }

    private static SortedSet<String> targetsOrNull(SortedSet<String> original, SortedSet<String> filtered) {
        return original == null ? null : filtered;
    }

    private static void mergeProvides(
            Map<String, SortedSet<String>> target, Map<String, ? extends Set<String>> source) {
        for (Map.Entry<String, ? extends Set<String>> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), key -> new TreeSet<>()).addAll(entry.getValue());
        }
    }

    private static final class ArtifactData {
        private final File file;
        private final Map<String, DescriptorEntry> descriptorsByName = new LinkedHashMap<>();
        private final Map<String, ServiceConfiguration> serviceConfigurations = new LinkedHashMap<>();
        private final Map<Integer, Set<String>> classes = new TreeMap<>();
        private boolean multiRelease;
        private String automaticModuleName;
        private boolean automaticModuleNameExplicit;

        private ArtifactData(File file) {
            this.file = file;
        }

        private void readManifest(Manifest manifest) {
            if (manifest == null) {
                return;
            }
            Attributes attributes = manifest.getMainAttributes();
            multiRelease = "true".equalsIgnoreCase(attributes.getValue("Multi-Release"));
            automaticModuleName = attributes.getValue("Automatic-Module-Name");
            automaticModuleNameExplicit = automaticModuleName != null;
        }

        private void addDescriptor(String name, ModuleDescriptorData descriptor, long time) {
            int release = 0;
            Matcher matcher = VERSIONED_MODULE_INFO.matcher(name);
            if (matcher.matches()) {
                release = Integer.parseInt(matcher.group(1));
            }
            descriptorsByName.put(name, new DescriptorEntry(name, release, descriptor, time));
        }

        private void addServiceConfiguration(ServiceConfiguration configuration) {
            serviceConfigurations.put(configuration.name, configuration);
        }

        private void addClass(String name) {
            Matcher matcher = VERSIONED_CLASS.matcher(name);
            int release = 0;
            String className;
            if (matcher.matches()) {
                release = Integer.parseInt(matcher.group(1));
                className = matcher.group(2);
            } else {
                className = name.substring(0, name.length() - ".class".length());
            }
            classes.computeIfAbsent(release, key -> new LinkedHashSet<>()).add(className);
        }

        private boolean hasClass(String className, int release) {
            if (classes.getOrDefault(0, Collections.emptySet()).contains(className)) {
                return true;
            }
            if (!multiRelease) {
                return false;
            }
            for (Map.Entry<Integer, Set<String>> entry : classes.entrySet()) {
                if (entry.getKey() > 0
                        && entry.getKey() <= release
                        && entry.getValue().contains(className)) {
                    return true;
                }
            }
            return false;
        }

        private DescriptorEntry effectiveDescriptor(int release) {
            return effectiveDescriptor(release, true);
        }

        private DescriptorEntry effectiveDescriptor(int release, boolean includedOnly) {
            DescriptorEntry result = null;
            for (DescriptorEntry descriptor : descriptorsByName.values()) {
                if (includedOnly && !descriptor.included) {
                    continue;
                }
                if (descriptor.physicalRelease > 0 && !multiRelease) {
                    continue;
                }
                if (descriptor.effectiveRelease() <= release
                        && (result == null || descriptor.physicalRelease > result.physicalRelease)) {
                    result = descriptor;
                }
            }
            return result;
        }

        private String effectiveOutputModuleName(int release) {
            DescriptorEntry descriptor = effectiveDescriptor(release);
            return descriptor == null ? automaticModuleName : descriptor.descriptor.name;
        }

        private String configuredModuleName(int release) {
            DescriptorEntry descriptor = effectiveDescriptor(release, false);
            return descriptor == null ? automaticModuleName : descriptor.descriptor.name;
        }
    }

    private static final class ServiceConfiguration {
        private final String name;
        private final String service;
        private final SortedSet<String> providers;
        private boolean included;

        private ServiceConfiguration(String name, String service, SortedSet<String> providers) {
            this.name = name;
            this.service = service;
            this.providers = providers;
        }
    }

    private static final class RetainedClass {
        private final ArtifactData origin;
        private final String className;
        private final int effectiveRelease;
        private final Set<String> references;
        private final Set<String> serviceUses;
        private final SortedSet<String> unresolvedServiceUses;

        private RetainedClass(
                ArtifactData origin,
                String className,
                int effectiveRelease,
                Set<String> references,
                Set<String> serviceUses,
                SortedSet<String> unresolvedServiceUses) {
            this.origin = origin;
            this.className = className;
            this.effectiveRelease = effectiveRelease;
            this.references = references;
            this.serviceUses = serviceUses;
            this.unresolvedServiceUses = unresolvedServiceUses;
        }
    }

    private static final class DescriptorEntry {
        private final String name;
        private final int physicalRelease;
        private final ModuleDescriptorData descriptor;
        private final long time;
        private boolean included;

        private DescriptorEntry(String name, int physicalRelease, ModuleDescriptorData descriptor, long time) {
            this.name = name;
            this.physicalRelease = physicalRelease;
            this.descriptor = descriptor;
            this.time = time;
        }

        private int effectiveRelease() {
            return Math.max(
                    physicalRelease == 0 ? FIRST_MODULE_RELEASE : physicalRelease,
                    classRelease(descriptor.classVersion));
        }
    }

    private static final class ModuleDescriptorData {
        private int classVersion;
        private int classAccess;
        private String name;
        private int access;
        private String version;
        private String mainClass;
        private String targetPlatform;
        private Integer resolutionFlags;
        private final Map<String, Require> requires = new TreeMap<>();
        private final Map<String, Export> exports = new TreeMap<>();
        private final Map<String, Open> opens = new TreeMap<>();
        private final SortedSet<String> uses = new TreeSet<>();
        private final Map<String, SortedSet<String>> provides = new TreeMap<>();
        private final SortedSet<String> packages = new TreeSet<>();
        private final SortedSet<String> attributes = new TreeSet<>();
    }

    private static final class MergedDescriptor {
        private final int release;
        private final long timestamp;
        private int classVersion;
        private int classAccess;
        private String name;
        private int access;
        private String version;
        private String mainClass;
        private String targetPlatform;
        private Integer resolutionFlags;
        private final Map<String, Require> requires = new TreeMap<>();
        private final Map<String, Export> primaryExports = new TreeMap<>();
        private final Map<String, Open> primaryOpens = new TreeMap<>();
        private final Map<String, Export> exports = new TreeMap<>();
        private final Map<String, Open> opens = new TreeMap<>();
        private final SortedSet<String> uses = new TreeSet<>();
        private final Map<String, SortedSet<String>> provides = new TreeMap<>();
        private final SortedSet<String> packages = new TreeSet<>();

        private MergedDescriptor(int release, DescriptorEntry primary) {
            this.release = release;
            this.timestamp = primary.time;
        }

        private boolean sameModuleSemantics(MergedDescriptor other) {
            return other != null
                    && Objects.equals(name, other.name)
                    && access == other.access
                    && Objects.equals(version, other.version)
                    && Objects.equals(mainClass, other.mainClass)
                    && Objects.equals(targetPlatform, other.targetPlatform)
                    && Objects.equals(resolutionFlags, other.resolutionFlags)
                    && Objects.equals(requires, other.requires)
                    && Objects.equals(exports, other.exports)
                    && Objects.equals(opens, other.opens)
                    && Objects.equals(uses, other.uses)
                    && Objects.equals(provides, other.provides)
                    && Objects.equals(packages, other.packages);
        }
    }

    private static final class ModuleTargetAttribute extends Attribute {
        private String targetPlatform;

        private ModuleTargetAttribute() {
            this(null);
        }

        private ModuleTargetAttribute(String targetPlatform) {
            super("ModuleTarget");
            this.targetPlatform = targetPlatform;
        }

        @Override
        protected Attribute read(
                ClassReader classReader,
                int offset,
                int length,
                char[] charBuffer,
                int codeAttributeOffset,
                Label[] labels) {
            return new ModuleTargetAttribute(classReader.readUTF8(offset, charBuffer));
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            return new ByteVector().putShort(classWriter.newUTF8(targetPlatform));
        }
    }

    private static final class ModuleResolutionAttribute extends Attribute {
        private int resolutionFlags;

        private ModuleResolutionAttribute() {
            this(0);
        }

        private ModuleResolutionAttribute(int resolutionFlags) {
            super("ModuleResolution");
            this.resolutionFlags = resolutionFlags;
        }

        @Override
        protected Attribute read(
                ClassReader classReader,
                int offset,
                int length,
                char[] charBuffer,
                int codeAttributeOffset,
                Label[] labels) {
            return new ModuleResolutionAttribute(classReader.readUnsignedShort(offset));
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            return new ByteVector().putShort(resolutionFlags);
        }
    }

    private static final class Require {
        private final String module;
        private final int access;
        private final String version;

        private Require(String module, int access, String version) {
            this.module = module;
            this.access = access;
            this.version = version;
        }

        private boolean isTransitive() {
            return (access & Opcodes.ACC_TRANSITIVE) != 0;
        }

        private static Require strongest(Require left, Require right) {
            int access = (left.access | right.access) & ~Opcodes.ACC_STATIC_PHASE;
            if ((left.access & Opcodes.ACC_STATIC_PHASE) != 0 && (right.access & Opcodes.ACC_STATIC_PHASE) != 0) {
                access |= Opcodes.ACC_STATIC_PHASE;
            }
            String version = left.version != null ? left.version : right.version;
            return new Require(left.module, access, version);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Require)) {
                return false;
            }
            Require other = (Require) object;
            return access == other.access
                    && Objects.equals(module, other.module)
                    && Objects.equals(version, other.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(module, access, version);
        }
    }

    private abstract static class PackageDirective {
        protected final String packaze;
        protected final int access;
        protected final SortedSet<String> targets;

        private PackageDirective(String packaze, int access, SortedSet<String> targets) {
            this.packaze = packaze;
            this.access = access;
            this.targets = targets;
        }

        @Override
        public boolean equals(Object object) {
            if (object == null || object.getClass() != getClass()) {
                return false;
            }
            PackageDirective other = (PackageDirective) object;
            return access == other.access
                    && Objects.equals(packaze, other.packaze)
                    && Objects.equals(targets, other.targets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packaze, access, targets);
        }
    }

    private static final class Export extends PackageDirective {
        private Export(String packaze, int access, SortedSet<String> targets) {
            super(packaze, access, targets);
        }
    }

    private static final class Open extends PackageDirective {
        private Open(String packaze, int access, SortedSet<String> targets) {
            super(packaze, access, targets);
        }
    }

    private static final class ServiceProvider {
        private final String service;
        private final String provider;

        private ServiceProvider(String service, String provider) {
            this.service = service;
            this.provider = provider;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof ServiceProvider)) {
                return false;
            }
            ServiceProvider other = (ServiceProvider) object;
            return Objects.equals(service, other.service) && Objects.equals(provider, other.provider);
        }

        @Override
        public int hashCode() {
            return Objects.hash(service, provider);
        }
    }

    private static final class Origin {
        private final File artifact;
        private final String entry;

        private Origin(File artifact, DescriptorEntry descriptor) {
            this(artifact, descriptor.name);
        }

        private Origin(File artifact, String entry) {
            this.artifact = artifact;
            this.entry = entry;
        }

        private String describe() {
            return artifact + "!" + entry;
        }
    }

    private static final class FloorReason implements Comparable<FloorReason> {
        private final String description;

        private FloorReason(String description) {
            this.description = description;
        }

        private static FloorReason provider(ServiceProvider provider, int release, Origin origin) {
            return new FloorReason("provider " + provider.provider + " for " + provider.service + " from "
                    + describe(origin) + " is not present before Java " + release);
        }

        private static FloorReason requirement(String module, int release, Origin origin) {
            return new FloorReason("transitive platform requirement " + module + " from " + describe(origin)
                    + " cannot be represented before Java " + release);
        }

        private static FloorReason exportedPackage(String packaze, int release) {
            return new FloorReason("exported package " + packaze + " is not present before Java " + release);
        }

        private static FloorReason openPackage(String packaze, int release) {
            return new FloorReason("open package " + packaze + " is not present before Java " + release);
        }

        private static String describe(Origin origin) {
            return origin == null ? "an embedded descriptor" : origin.describe();
        }

        private String describe() {
            return description;
        }

        @Override
        public int compareTo(FloorReason other) {
            return description.compareTo(other.description);
        }
    }
}
