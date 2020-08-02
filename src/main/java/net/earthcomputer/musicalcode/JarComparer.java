package net.earthcomputer.musicalcode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarComparer {
    public static void compare(JarFile fromJar, JarFile toJar, MemberPattern memberPattern, Consumer<String> outputLog, Consumer<String> errorLog) {
        for (String className : memberPattern.getClasses()) {
            JarEntry fromEntry = fromJar.getJarEntry(className + ".class");
            JarEntry toEntry = toJar.getJarEntry(className + ".class");
            if (fromEntry == null) {
                if (toEntry != null) {
                    visitAddedRemoved(toJar, toEntry, memberPattern, "added", outputLog);
                }
            } else if (toEntry == null) {
                visitAddedRemoved(fromJar, fromEntry, memberPattern, "removed", outputLog);
            } else {
                ClassReader fromReader, toReader;
                try {
                    fromReader = new ClassReader(fromJar.getInputStream(fromEntry));
                    toReader = new ClassReader(toJar.getInputStream(toEntry));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                ClassNode fromClass = new ClassNode();
                ClassNode toClass = new ClassNode();
                fromReader.accept(fromClass, ClassReader.SKIP_FRAMES);
                toReader.accept(toClass, ClassReader.SKIP_FRAMES);
                compareClasses(fromClass, toClass, memberPattern, outputLog);
            }
        }

        memberPattern.assertUsed(errorLog);
    }

    private static void visitAddedRemoved(JarFile jar, JarEntry entry, MemberPattern memberPattern, String action, Consumer<String> outputLog) {
        ClassReader reader;
        try {
            reader = new ClassReader(jar.getInputStream(entry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);

        outputLog.accept("Class " + node.name + " was " + action);

        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                if (memberPattern.matchesField(node.name, field)) {
                    outputLog.accept("Field " + node.name + "." + field.name + " was " + action);
                }
            }
        }

        if (node.methods != null) {
            for (MethodNode method : node.methods) {
                if (memberPattern.matchesMethod(node.name, method)) {
                    outputLog.accept("Method " + node.name + "." + method.name + method.desc + " was " + action);
                }
            }
        }
    }

    private static void compareClasses(ClassNode fromClass, ClassNode toClass, MemberPattern memberPattern, Consumer<String> outputLog) {
        Map<String, FieldNode> fromFields = new LinkedHashMap<>();
        Map<String, FieldNode> toFields = new LinkedHashMap<>();
        if (fromClass.fields != null) {
            for (FieldNode field : fromClass.fields) {
                fromFields.put(field.name, field);
            }
        }
        if (toClass.fields != null) {
            for (FieldNode field : toClass.fields) {
                toFields.put(field.name, field);
            }
        }
        for (FieldNode fromField : fromFields.values()) {
            if (memberPattern.matchesField(fromClass.name, fromField)) {
                if (toFields.containsKey(fromField.name)) {
                    if (hasFieldChanged(fromField, toFields.get(fromField.name))) {
                        outputLog.accept("Field " + fromClass.name + "." + fromField.name + " was changed");
                    }
                } else {
                    outputLog.accept("Field " + fromClass.name + "." + fromField.name + " was removed");
                }
            }
        }
        for (FieldNode toField : toFields.values()) {
            if (!fromFields.containsKey(toField.name)) {
                if (memberPattern.matchesField(fromClass.name, toField)) {
                    outputLog.accept("Field " + toClass.name + "." + toField.name + " was added");
                }
            }
        }

        Map<String, MethodNode> fromMethods = new LinkedHashMap<>();
        Map<String, MethodNode> toMethods = new LinkedHashMap<>();
        if (fromClass.methods != null) {
            for (MethodNode method : fromClass.methods) {
                fromMethods.put(method.name + method.desc, method);
            }
        }
        if (toClass.methods != null) {
            for (MethodNode method : toClass.methods) {
                toMethods.put(method.name + method.desc, method);
            }
        }
        for (MethodNode fromMethod : fromMethods.values()) {
            if (memberPattern.matchesMethod(fromClass.name, fromMethod)) {
                if (toMethods.containsKey(fromMethod.name + fromMethod.desc)) {
                    if (hasMethodChanged(fromMethod, toMethods.get(fromMethod.name + fromMethod.desc))) {
                        outputLog.accept("Method " + fromClass.name + "." + fromMethod.name + fromMethod.desc + " was changed");
                    }
                } else {
                    outputLog.accept("Method " + fromClass.name + "." + fromMethod.name + fromMethod.desc + " was removed");
                }
            }
        }
        for (MethodNode toMethod : toMethods.values()) {
            if (!fromMethods.containsKey(toMethod.name + toMethod.desc)) {
                if (memberPattern.matchesMethod(fromClass.name, toMethod)) {
                    outputLog.accept("Method " + toClass.name + "." + toMethod.name + toMethod.desc + " was added");
                }
            }
        }
    }

    private static boolean hasFieldChanged(FieldNode fromField, FieldNode toField) {
        if (fromField.access != toField.access) {
            return true;
        }
        if (!fromField.desc.equals(toField.desc)) {
            return true;
        }
        if (!Objects.equals(fromField.signature, toField.signature)) {
            return true;
        }
        if (!Objects.equals(fromField.value, toField.value)) {
            return true;
        }
        if (haveAnnotationsChanged(fromField.invisibleAnnotations, toField.invisibleAnnotations)) {
            return true;
        }
        if (haveAnnotationsChanged(fromField.visibleAnnotations, toField.visibleAnnotations)) {
            return true;
        }
        return false;
    }

    private static boolean hasMethodChanged(MethodNode fromMethod, MethodNode toMethod) {
        if (fromMethod.access != toMethod.access) {
            return true;
        }
        if (!Objects.equals(fromMethod.signature, toMethod.signature)) {
            return true;
        }
        if (!Objects.equals(fromMethod.exceptions, toMethod.exceptions)) {
            return true;
        }
        if (fromMethod.parameters != null || toMethod.parameters != null) {
            if (fromMethod.parameters == null || toMethod.parameters == null) {
                return true;
            }
            if (fromMethod.parameters.size() != toMethod.parameters.size()) {
                return true;
            }
            for (int i = 0; i < fromMethod.parameters.size(); i++) {
                if (fromMethod.parameters.get(i).access != toMethod.parameters.get(i).access) {
                    return true;
                }
            }
        }
        if (haveAnnotationsChanged(fromMethod.invisibleAnnotations, toMethod.invisibleAnnotations)) {
            return true;
        }
        if (haveAnnotationsChanged(fromMethod.visibleAnnotations, toMethod.visibleAnnotations)) {
            return true;
        }
        if (hasAnnotationValueChanged(fromMethod.annotationDefault, toMethod.annotationDefault)) {
            return true;
        }
        if (fromMethod.visibleAnnotableParameterCount != toMethod.visibleAnnotableParameterCount) {
            return true;
        }
        if (fromMethod.visibleParameterAnnotations != null || toMethod.visibleParameterAnnotations != null) {
            if (fromMethod.visibleParameterAnnotations == null || toMethod.visibleParameterAnnotations == null) {
                return true;
            }
            if (fromMethod.visibleParameterAnnotations.length != toMethod.visibleParameterAnnotations.length) {
                return true;
            }
            for (int i = 0; i < fromMethod.visibleParameterAnnotations.length; i++) {
                if (hasAnnotationValueChanged(fromMethod.visibleParameterAnnotations[i], toMethod.visibleParameterAnnotations[i])) {
                    return true;
                }
            }
        }
        if (fromMethod.invisibleAnnotableParameterCount != toMethod.invisibleAnnotableParameterCount) {
            return true;
        }
        if (fromMethod.invisibleParameterAnnotations != null || toMethod.invisibleParameterAnnotations != null) {
            if (fromMethod.invisibleParameterAnnotations == null || toMethod.invisibleParameterAnnotations == null) {
                return true;
            }
            if (fromMethod.invisibleParameterAnnotations.length != toMethod.invisibleParameterAnnotations.length) {
                return true;
            }
            for (int i = 0; i < fromMethod.invisibleParameterAnnotations.length; i++) {
                if (hasAnnotationValueChanged(fromMethod.invisibleParameterAnnotations[i], toMethod.invisibleParameterAnnotations[i])) {
                    return true;
                }
            }
        }
        if (haveInstructionsChanged(fromMethod.instructions, toMethod.instructions)) {
            return true;
        }
        return false;
    }

    private static boolean haveAnnotationsChanged(List<AnnotationNode> fromAnnotations, List<AnnotationNode> toAnnotations) {
        if (fromAnnotations == null && toAnnotations == null) {
            return false;
        }
        if (fromAnnotations == null || toAnnotations == null) {
            return true;
        }
        if (fromAnnotations.size() != toAnnotations.size()) {
            return true;
        }
        for (int i = 0; i < fromAnnotations.size(); i++) {
            if (hasAnnotationChanged(fromAnnotations.get(i), toAnnotations.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotationChanged(AnnotationNode fromNode, AnnotationNode toNode) {
        if (!fromNode.desc.equals(toNode.desc)) {
            return true;
        }
        if (hasAnnotationValueChanged(fromNode.values, toNode.values)) {
            return true;
        }
        return false;
    }

    private static boolean hasAnnotationValueChanged(Object fromVal, Object toVal) {
        if (fromVal == null && toVal == null) {
            return false;
        }
        if (fromVal == null || toVal == null) {
            return true;
        }
        if (fromVal instanceof List) {
            if (!(toVal instanceof List)) {
                return true;
            }
            List<?> fromList = (List<?>) fromVal;
            List<?> toList = (List<?>) toVal;
            if (fromList.size() != toList.size()) {
                return true;
            }
            for (int i = 0; i < fromList.size(); i++) {
                if (hasAnnotationValueChanged(fromList.get(i), toList.get(i))) {
                    return true;
                }
            }
            return false;
        }
        if (fromVal instanceof AnnotationNode) {
            if (!(toVal instanceof AnnotationNode)) {
                return true;
            }
            return hasAnnotationChanged((AnnotationNode) fromVal, (AnnotationNode) toVal);
        }

        return !fromVal.equals(toVal);
    }

    private static boolean haveInstructionsChanged(InsnList fromInstructions, InsnList toInstructions) {
        if (fromInstructions == null && toInstructions == null) {
            return false;
        }
        if (fromInstructions == null || toInstructions == null) {
            return true;
        }

        // store label indices to test label equivalence
        Map<LabelNode, Integer> fromLabelIndices = new HashMap<>();
        Map<LabelNode, Integer> toLabelIndices = new HashMap<>();
        List<JumpInsnNode> fromJumps = new ArrayList<>();
        List<JumpInsnNode> toJumps = new ArrayList<>();
        List<TableSwitchInsnNode> fromTableSwitches = new ArrayList<>();
        List<TableSwitchInsnNode> toTableSwitches = new ArrayList<>();
        List<LookupSwitchInsnNode> fromLookupSwitches = new ArrayList<>();
        List<LookupSwitchInsnNode> toLookupSwitches = new ArrayList<>();

        int insnIndex = 0;
        AbstractInsnNode fromInsn = fromInstructions.getFirst();
        AbstractInsnNode toInsn = toInstructions.getFirst();
        instructionLoop: while (fromInsn != null) {
            // skip non-instruction nodes
            while (isInstructionIgnored(fromInsn)) {
                fromInsn = fromInsn.getNext();
                if (fromInsn == null) {
                    break instructionLoop;
                }
            }
            if (toInsn == null) {
                return true;
            }
            while (isInstructionIgnored(toInsn)) {
                toInsn = toInsn.getNext();
                if (toInsn == null) {
                    return true;
                }
            }

            // we are now comparing two instruction nodes which should be the same
            insnIndex++;

            if (fromInsn.getOpcode() != toInsn.getOpcode()) {
                return true;
            }
            switch (fromInsn.getType()) {
                case AbstractInsnNode.INSN: {
                    // nothing to check
                    break;
                }
                case AbstractInsnNode.INT_INSN: {
                    IntInsnNode fromI = (IntInsnNode) fromInsn;
                    IntInsnNode toI = (IntInsnNode) toInsn;
                    if (fromI.operand != toI.operand) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.VAR_INSN: {
                    VarInsnNode fromI = (VarInsnNode) fromInsn;
                    VarInsnNode toI = (VarInsnNode) toInsn;
                    if (fromI.var != toI.var) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.TYPE_INSN: {
                    TypeInsnNode fromI = (TypeInsnNode) fromInsn;
                    TypeInsnNode toI = (TypeInsnNode) toInsn;
                    if (!fromI.desc.equals(toI.desc)) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.FIELD_INSN: {
                    FieldInsnNode fromI = (FieldInsnNode) fromInsn;
                    FieldInsnNode toI = (FieldInsnNode) toInsn;
                    if (!fromI.owner.equals(toI.owner)) {
                        return true;
                    }
                    if (!fromI.name.equals(toI.name)) {
                        return true;
                    }
                    if (!fromI.desc.equals(toI.desc)) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.METHOD_INSN: {
                    MethodInsnNode fromI = (MethodInsnNode) fromInsn;
                    MethodInsnNode toI = (MethodInsnNode) toInsn;
                    if (!fromI.owner.equals(toI.owner)) {
                        return true;
                    }
                    if (!fromI.name.equals(toI.name)) {
                        return true;
                    }
                    if (!fromI.desc.equals(toI.desc)) {
                        return true;
                    }
                    if (fromI.itf != toI.itf) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
                    InvokeDynamicInsnNode fromI = (InvokeDynamicInsnNode) fromInsn;
                    InvokeDynamicInsnNode toI = (InvokeDynamicInsnNode) toInsn;
                    if (!fromI.name.equals(toI.name)) {
                        return true;
                    }
                    if (!fromI.desc.equals(toI.desc)) {
                        return true;
                    }
                    if (fromI.bsm.getTag() != toI.bsm.getTag()) {
                        return true;
                    }
                    if (!fromI.bsm.getOwner().equals(toI.bsm.getOwner())) {
                        return true;
                    }
                    if (!fromI.bsm.getName().equals(toI.bsm.getName())) {
                        return true;
                    }
                    if (!fromI.bsm.getDesc().equals(toI.bsm.getDesc())) {
                        return true;
                    }
                    if (fromI.bsm.isInterface() != toI.bsm.isInterface()) {
                        return true;
                    }
                    if (fromI.bsmArgs.length != toI.bsmArgs.length) {
                        return true;
                    }
                    for (int i = 0; i < fromI.bsmArgs.length; i++) {
                        if (!Objects.equals(fromI.bsmArgs[i], toI.bsmArgs[i])) {
                            return true;
                        }
                    }
                    break;
                }
                case AbstractInsnNode.JUMP_INSN: {
                    fromJumps.add((JumpInsnNode) fromInsn);
                    toJumps.add((JumpInsnNode) toInsn);
                    break;
                }
                case AbstractInsnNode.LABEL: {
                    fromLabelIndices.put((LabelNode) fromInsn, insnIndex);
                    toLabelIndices.put((LabelNode) toInsn, insnIndex);
                    break;
                }
                case AbstractInsnNode.LDC_INSN: {
                    LdcInsnNode fromI = (LdcInsnNode) fromInsn;
                    LdcInsnNode toI = (LdcInsnNode) toInsn;
                    if (!Objects.equals(fromI.cst, toI.cst)) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.IINC_INSN: {
                    IincInsnNode fromI = (IincInsnNode) fromInsn;
                    IincInsnNode toI = (IincInsnNode) toInsn;
                    if (fromI.var != toI.var) {
                        return true;
                    }
                    if (fromI.incr != toI.incr) {
                        return true;
                    }
                    break;
                }
                case AbstractInsnNode.TABLESWITCH_INSN: {
                    TableSwitchInsnNode fromI = (TableSwitchInsnNode) fromInsn;
                    TableSwitchInsnNode toI = (TableSwitchInsnNode) toInsn;
                    if (fromI.min != toI.min) {
                        return true;
                    }
                    if (fromI.max != toI.max) {
                        return true;
                    }
                    if (fromI.labels.size() != toI.labels.size()) {
                        return true;
                    }
                    fromTableSwitches.add(fromI);
                    toTableSwitches.add(toI);
                    break;
                }
                case AbstractInsnNode.LOOKUPSWITCH_INSN: {
                    LookupSwitchInsnNode fromI = (LookupSwitchInsnNode) fromInsn;
                    LookupSwitchInsnNode toI = (LookupSwitchInsnNode) toInsn;
                    if (!fromI.keys.equals(toI.keys)) {
                        return true;
                    }
                    fromLookupSwitches.add(fromI);
                    toLookupSwitches.add(toI);
                    break;
                }
                case AbstractInsnNode.MULTIANEWARRAY_INSN: {
                    MultiANewArrayInsnNode fromI = (MultiANewArrayInsnNode) fromInsn;
                    MultiANewArrayInsnNode toI = (MultiANewArrayInsnNode) toInsn;
                    if (!fromI.desc.equals(toI.desc)) {
                        return true;
                    }
                    if (fromI.dims != toI.dims) {
                        return true;
                    }
                    break;
                }
                default: throw new AssertionError("Unknown AbstractInsnNode type: " + fromInsn.getType());
            }
        }

        // check for trailing instructions in toInstructions
        while (toInsn != null && isInstructionIgnored(toInsn)) {
            toInsn = toInsn.getNext();
        }
        if (toInsn != null) {
            return true;
        }

        // check labels against label indices
        for (int i = 0; i < fromJumps.size(); i++) {
            JumpInsnNode fromI = fromJumps.get(i);
            JumpInsnNode toI = toJumps.get(i);
            if (!fromLabelIndices.get(fromI.label).equals(toLabelIndices.get(toI.label))) {
                return true;
            }
        }
        for (int i = 0; i < fromTableSwitches.size(); i++) {
            TableSwitchInsnNode fromI = fromTableSwitches.get(i);
            TableSwitchInsnNode toI = toTableSwitches.get(i);
            if (!fromLabelIndices.get(fromI.dflt).equals(toLabelIndices.get(toI.dflt))) {
                return true;
            }
            for (int j = 0; j < fromI.labels.size(); i++) {
                if (!fromLabelIndices.get(fromI.labels.get(j)).equals(toLabelIndices.get(toI.labels.get(j)))) {
                    return true;
                }
            }
        }
        for (int i = 0; i < fromLookupSwitches.size(); i++) {
            LookupSwitchInsnNode fromI = fromLookupSwitches.get(i);
            LookupSwitchInsnNode toI = toLookupSwitches.get(i);
            if (!fromLabelIndices.get(fromI.dflt).equals(toLabelIndices.get(toI.dflt))) {
                return true;
            }
            for (int j = 0; j < fromI.labels.size(); i++) {
                if (!fromLabelIndices.get(fromI.labels.get(j)).equals(toLabelIndices.get(toI.labels.get(j)))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isInstructionIgnored(AbstractInsnNode insn) {
        return insn.getOpcode() == -1 && insn.getType() != AbstractInsnNode.LABEL;
    }
}
