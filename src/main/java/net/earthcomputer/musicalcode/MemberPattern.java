package net.earthcomputer.musicalcode;

import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public abstract class MemberPattern {
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile(IDENTIFIER_PATTERN.pattern() + "|<init>|<clinit>");
    private static final Pattern INTERNAL_NAME_PATTERN = Pattern.compile(IDENTIFIER_PATTERN.pattern() + "(?:/" + IDENTIFIER_PATTERN.pattern() + ")*");
    private static final Pattern TYPE_DESC_PATTERN = Pattern.compile("\\[*(?:[BCDFIJLZ]|L" + INTERNAL_NAME_PATTERN.pattern() + ";)");
    private static final Pattern METHOD_DESC_PATTERN = Pattern.compile("\\((?:" + TYPE_DESC_PATTERN.pattern() + ")*\\)(?:V|(?:" + TYPE_DESC_PATTERN.pattern() + "))");

    public abstract Collection<String> getClasses();
    public abstract boolean matchesField(String className, FieldNode field);
    public abstract boolean matchesMethod(String className, MethodNode method);
    public abstract void assertUsed(Consumer<String> errorLog);

    public static MemberPattern parse(File file, Remapper yarn2intermediary) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config", e);
        }
        List<MemberPattern> memberPatterns = new ArrayList<>();
        for (String line : lines) {
            int hashIndex = line.indexOf('#');
            if (hashIndex != -1) {
                line = line.substring(0, hashIndex);
            }
            line = line.replace(" ", "");
            if (!line.isEmpty()) {
                memberPatterns.add(parse(line, yarn2intermediary));
            }
        }

        return new CombinedMemberPattern(memberPatterns);
    }

    public static MemberPattern parse(String pattern, Remapper yarn2Intermediary) {
        int dotIndex = pattern.indexOf('.');
        if (dotIndex == -1) {
            if (!INTERNAL_NAME_PATTERN.matcher(pattern).matches()) {
                throw new IllegalArgumentException(pattern + " does not match the pattern for class names");
            }
            return new ClassPattern(yarn2Intermediary.map(pattern));
        }

        String className = pattern.substring(0, dotIndex);
        String member = pattern.substring(dotIndex + 1);
        if (!INTERNAL_NAME_PATTERN.matcher(className).matches()) {
            throw new IllegalArgumentException(className + " does not match the pattern for class names");
        }
        if (member.equals("*")) {
            return new ClassPattern(yarn2Intermediary.map(className));
        }

        int parenthesisIndex = member.indexOf('(');
        if (parenthesisIndex == -1) {
            int colonIndex = member.indexOf(":");
            if (colonIndex == -1) {
                throw new IllegalArgumentException("Member missing descriptor: " + member);
            }
            String fieldName = member.substring(0, colonIndex);
            String fieldDesc = member.substring(colonIndex + 1);
            if (!IDENTIFIER_PATTERN.matcher(fieldName).matches()) {
                throw new IllegalArgumentException(fieldName + " does not match the pattern for field names");
            }
            if (!TYPE_DESC_PATTERN.matcher(fieldDesc).matches()) {
                throw new IllegalArgumentException(fieldDesc + " does not match the pattern for field descriptors");
            }
            return new FieldPattern(yarn2Intermediary.map(className), yarn2Intermediary.mapFieldName(className, fieldName, fieldDesc));
        }

        String methodName = member.substring(0, parenthesisIndex);
        String methodDesc = member.substring(parenthesisIndex);
        if (!METHOD_NAME_PATTERN.matcher(methodName).matches()) {
            throw new IllegalArgumentException(methodName + " does not match the pattern for method names");
        }
        if (!METHOD_DESC_PATTERN.matcher(methodDesc).matches()) {
            throw new IllegalArgumentException(methodDesc + " does not match the pattern for method descriptors");
        }
        return new MethodPattern(
                yarn2Intermediary.map(className),
                yarn2Intermediary.mapMethodName(className, methodName, methodDesc),
                yarn2Intermediary.mapMethodDesc(methodDesc)
        );
    }

    private static class CombinedMemberPattern extends MemberPattern {
        private final List<MemberPattern> children;

        private CombinedMemberPattern(List<MemberPattern> children) {
            this.children = children;
        }

        @Override
        public Collection<String> getClasses() {
            Set<String> classes = new LinkedHashSet<>();
            for (MemberPattern child : children) {
                classes.addAll(child.getClasses());
            }
            return classes;
        }

        @Override
        public boolean matchesField(String className, FieldNode field) {
            return children.stream().anyMatch(child -> child.matchesField(className, field));
        }

        @Override
        public boolean matchesMethod(String className, MethodNode method) {
            return children.stream().anyMatch(child -> child.matchesMethod(className, method));
        }

        @Override
        public void assertUsed(Consumer<String> errorLog) {
            for (MemberPattern child : children) {
                child.assertUsed(errorLog);
            }
        }
    }

    private static class ClassPattern extends MemberPattern {
        private final String className;
        private boolean used;

        private ClassPattern(String className) {
            this.className = className;
        }

        @Override
        public Collection<String> getClasses() {
            return Collections.singletonList(className);
        }

        @Override
        public boolean matchesField(String className, FieldNode field) {
            if (this.className.equals(className)) {
                used = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean matchesMethod(String className, MethodNode method) {
            if (this.className.equals(className)) {
                used = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void assertUsed(Consumer<String> errorLog) {
            if (!used) {
                errorLog.accept("Class " + className + " not found");
            }
        }
    }

    private static class FieldPattern extends MemberPattern {
        private final String className;
        private final String fieldName;
        private boolean used;

        private FieldPattern(String className, String fieldName) {
            this.className = className;
            this.fieldName = fieldName;
        }

        @Override
        public Collection<String> getClasses() {
            return Collections.singletonList(className);
        }

        @Override
        public boolean matchesField(String className, FieldNode field) {
            if (this.className.equals(className) && this.fieldName.equals(field.name)) {
                used = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean matchesMethod(String className, MethodNode method) {
            return false;
        }

        @Override
        public void assertUsed(Consumer<String> errorLog) {
            if (!used) {
                errorLog.accept("Field " + className + "." + fieldName + " not found");
            }
        }
    }

    private static class MethodPattern extends MemberPattern {
        private final String className;
        private final String methodName;
        private final String methodDesc;
        private boolean used;

        private MethodPattern(String className, String methodName, String methodDesc) {
            this.className = className;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        public Collection<String> getClasses() {
            return Collections.singletonList(className);
        }

        @Override
        public boolean matchesField(String className, FieldNode field) {
            return false;
        }

        @Override
        public boolean matchesMethod(String className, MethodNode method) {
            if (this.className.equals(className) && this.methodName.equals(method.name) && this.methodDesc.equals(method.desc)) {
                used = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void assertUsed(Consumer<String> errorLog) {
            if (!used) {
                errorLog.accept("Method " + className + "." + methodName + methodDesc + " not found");
            }
        }
    }
}
