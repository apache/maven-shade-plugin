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

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * Extracts bytecode-visible dependencies and service uses from classes retained from automatic modules.
 */
final class AutomaticModuleAnalyzer {
    private static final String SERVICE_LOADER = "java/util/ServiceLoader";

    private AutomaticModuleAnalyzer() {}

    static Analysis analyze(byte[] bytecode) {
        Set<String> references = new TreeSet<>();
        ClassVisitor collector = new ClassRemapper(new ClassWriter(0), new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                if (internalName != null) {
                    references.add(internalName);
                }
                return internalName;
            }
        });
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        Set<String> serviceUses = new TreeSet<>();
        SortedSet<String> unresolvedServiceUses = new TreeSet<>();
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        reader.accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : classNode.methods) {
            analyzeServiceUses(classNode.name, method, serviceUses, unresolvedServiceUses);
        }
        return new Analysis(reader.readShort(6), references, serviceUses, unresolvedServiceUses);
    }

    private static void analyzeServiceUses(
            String className, MethodNode method, Set<String> serviceUses, SortedSet<String> unresolvedServiceUses) {
        boolean hasDirectInvocation = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode
                    && SERVICE_LOADER.equals(((MethodInsnNode) instruction).owner)
                    && ((MethodInsnNode) instruction).name.startsWith("load")) {
                hasDirectInvocation = true;
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                if (isServiceLoaderHandle(dynamic.bsm) || containsServiceLoaderHandle(dynamic.bsmArgs)) {
                    unresolvedServiceUses.add(
                            "ServiceLoader method handle in " + toClassName(className) + '.' + method.name);
                }
            } else if (instruction instanceof LdcInsnNode && isServiceLoaderHandle(((LdcInsnNode) instruction).cst)) {
                unresolvedServiceUses.add(
                        "ServiceLoader method handle in " + toClassName(className) + '.' + method.name);
            }
        }
        if (!hasDirectInvocation) {
            return;
        }

        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new ClassLiteralInterpreter()).analyze(className, method);
        } catch (AnalyzerException e) {
            unresolvedServiceUses.add(
                    "ServiceLoader call in " + toClassName(className) + '.' + method.name + method.desc);
            return;
        }

        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode invocation = (MethodInsnNode) instruction;
            int serviceArgument = serviceArgumentIndex(invocation);
            if (serviceArgument < 0) {
                if (SERVICE_LOADER.equals(invocation.owner) && invocation.name.startsWith("load")) {
                    unresolvedServiceUses.add("ServiceLoader." + invocation.name + invocation.desc + " in "
                            + toClassName(className) + '.' + method.name);
                }
                continue;
            }

            Frame<BasicValue> frame = frames[index];
            Type[] arguments = Type.getArgumentTypes(invocation.desc);
            int stackIndex = frame == null ? -1 : frame.getStackSize() - arguments.length + serviceArgument;
            BasicValue value = stackIndex < 0 ? null : frame.getStack(stackIndex);
            if (value instanceof ClassLiteralValue) {
                serviceUses.addAll(((ClassLiteralValue) value).classNames);
            } else {
                unresolvedServiceUses.add("ServiceLoader." + invocation.name + invocation.desc + " in "
                        + toClassName(className) + '.' + method.name);
            }
        }
    }

    private static int serviceArgumentIndex(MethodInsnNode invocation) {
        if (!SERVICE_LOADER.equals(invocation.owner) || invocation.getOpcode() != Opcodes.INVOKESTATIC) {
            return -1;
        }
        if ("loadInstalled".equals(invocation.name)
                && "(Ljava/lang/Class;)Ljava/util/ServiceLoader;".equals(invocation.desc)) {
            return 0;
        }
        if (!"load".equals(invocation.name)) {
            return -1;
        }
        if ("(Ljava/lang/Class;)Ljava/util/ServiceLoader;".equals(invocation.desc)
                || "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;".equals(invocation.desc)) {
            return 0;
        }
        if ("(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;".equals(invocation.desc)) {
            return 1;
        }
        return -1;
    }

    private static boolean containsServiceLoaderHandle(Object[] values) {
        for (Object value : values) {
            if (isServiceLoaderHandle(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isServiceLoaderHandle(Object value) {
        if (!(value instanceof Handle)) {
            return false;
        }
        Handle handle = (Handle) value;
        return SERVICE_LOADER.equals(handle.getOwner()) && handle.getName().startsWith("load");
    }

    private static String toClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    static final class Analysis {
        final int classVersion;
        final Set<String> references;
        final Set<String> serviceUses;
        final SortedSet<String> unresolvedServiceUses;

        private Analysis(
                int classVersion,
                Set<String> references,
                Set<String> serviceUses,
                SortedSet<String> unresolvedServiceUses) {
            this.classVersion = classVersion;
            this.references = references;
            this.serviceUses = serviceUses;
            this.unresolvedServiceUses = unresolvedServiceUses;
        }
    }

    private static final class ClassLiteralInterpreter extends BasicInterpreter {
        private ClassLiteralInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            if (instruction.getOpcode() == Opcodes.LDC) {
                Object value = ((LdcInsnNode) instruction).cst;
                if (value instanceof Type && ((Type) value).getSort() == Type.OBJECT) {
                    return new ClassLiteralValue(Collections.singleton(((Type) value).getInternalName()));
                }
            }
            return super.newOperation(instruction);
        }

        @Override
        public BasicValue merge(BasicValue first, BasicValue second) {
            if (first instanceof ClassLiteralValue && second instanceof ClassLiteralValue) {
                SortedSet<String> classNames = new TreeSet<>(((ClassLiteralValue) first).classNames);
                classNames.addAll(((ClassLiteralValue) second).classNames);
                return classNames.equals(((ClassLiteralValue) first).classNames)
                        ? first
                        : new ClassLiteralValue(classNames);
            }
            return super.merge(first, second);
        }
    }

    private static final class ClassLiteralValue extends BasicValue {
        private final SortedSet<String> classNames;

        private ClassLiteralValue(Collection<String> classNames) {
            super(Type.getObjectType("java/lang/Class"));
            this.classNames = Collections.unmodifiableSortedSet(new TreeSet<>(classNames));
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof ClassLiteralValue && classNames.equals(((ClassLiteralValue) object).classNames);
        }

        @Override
        public int hashCode() {
            return classNames.hashCode();
        }
    }
}
