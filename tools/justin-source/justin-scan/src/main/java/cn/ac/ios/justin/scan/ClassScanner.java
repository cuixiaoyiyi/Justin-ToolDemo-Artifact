package cn.ac.ios.justin.scan;

import cn.ac.ios.justin.core.target.AnnotationInfo;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.FieldInfo;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.generics.SignatureParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight ASM pass over one class file: header, generic signatures, annotations, fields, methods,
 * parameter names and first line numbers. Instructions are not decoded (except to find line numbers
 * and local variable names), so this is cheap enough to run over a whole classpath.
 */
public final class ClassScanner {

    private ClassScanner() {}

    public static ClassTarget scan(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        Visitor v = new Visitor();
        reader.accept(v, ClassReader.SKIP_FRAMES);
        return v.result();
    }

    private static final class Visitor extends ClassVisitor {
        private String binaryName;
        private int access;
        private int majorVersion;
        private SignatureParser.ClassSig sig;
        private String sourceFile;
        private String outerClass;
        private final List<AnnotationInfo> annotations = new ArrayList<>();
        private final List<FieldInfo> fields = new ArrayList<>();
        private final List<MethodTarget> methods = new ArrayList<>();

        Visitor() { super(Opcodes.ASM9); }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.majorVersion = version & 0xFFFF;
            this.access = access;
            this.binaryName = name.replace('/', '.');
            this.sig = SignatureParser.parseClassSignature(signature, superName, interfaces);
        }

        @Override public void visitSource(String source, String debug) { this.sourceFile = source; }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) { this.outerClass = owner.replace('/', '.'); }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (name.replace('/', '.').equals(binaryName)) {
                if (outerName != null) this.outerClass = outerName.replace('/', '.');
                // inner-class access flags carry static/private information the outer header lacks
                this.access |= (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED));
                if ((access & Opcodes.ACC_PUBLIC) == 0) this.access &= ~Opcodes.ACC_PUBLIC;
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new AnnotationCollector(descriptor, visible, annotations::add);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            TypeRef type = SignatureParser.parseType(signature != null ? signature : descriptor);
            List<AnnotationInfo> anns = new ArrayList<>();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return new AnnotationCollector(desc, visible, anns::add);
                }
                @Override public void visitEnd() {
                    fields.add(new FieldInfo(name, type, access, anns, value));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodCollector(access, name, descriptor, signature, exceptions);
        }

        ClassTarget result() {
            return new ClassTarget(binaryName, access, sig.typeParams(), sig.superClass(), sig.interfaces(),
                    methods, fields, annotations, sourceFile, outerClass, majorVersion);
        }

        /** Collects everything about one method; parameter names from MethodParameters first, then LocalVariableTable. */
        private final class MethodCollector extends MethodVisitor {
            private final int access;
            private final String name;
            private final String descriptor;
            private final SignatureParser.MethodSig sig;
            private final List<String> exceptions = new ArrayList<>();
            private final List<AnnotationInfo> annotations = new ArrayList<>();
            private final Map<Integer, List<AnnotationInfo>> paramAnnotations = new HashMap<>();
            private final String[] paramNamesFromAttr;
            private final Map<Integer, String> localNamesBySlot = new HashMap<>();
            private int firstLine = -1;
            private int paramAttrIndex = 0;

            MethodCollector(int access, String name, String descriptor, String signature, String[] exceptionsArr) {
                super(Opcodes.ASM9);
                this.access = access;
                this.name = name;
                this.descriptor = descriptor;
                this.sig = SignatureParser.parseMethodSignature(signature, descriptor, Visitor.this.sig.typeParams());
                if (exceptionsArr != null) for (String e : exceptionsArr) exceptions.add(e.replace('/', '.'));
                this.paramNamesFromAttr = new String[sig.params().size()];
            }

            @Override public void visitParameter(String pname, int paccess) {
                if (paramAttrIndex < paramNamesFromAttr.length) paramNamesFromAttr[paramAttrIndex++] = pname;
            }

            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return new AnnotationCollector(desc, visible, annotations::add);
            }

            @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
                return new AnnotationCollector(desc, visible,
                        a -> paramAnnotations.computeIfAbsent(parameter, k -> new ArrayList<>()).add(a));
            }

            @Override public void visitLineNumber(int line, Label start) {
                if (firstLine < 0 || line < firstLine) firstLine = line;
            }

            @Override public void visitLocalVariable(String lname, String desc, String lsig, Label start, Label end, int index) {
                localNamesBySlot.putIfAbsent(index, lname);
            }

            @Override public void visitEnd() {
                List<Parameter> params = new ArrayList<>();
                Type[] argTypes = Type.getArgumentTypes(descriptor);
                int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                for (int i = 0; i < sig.params().size(); i++) {
                    String pname = i < paramNamesFromAttr.length ? paramNamesFromAttr[i] : null;
                    if (pname == null) pname = localNamesBySlot.get(slot);
                    if (pname == null || !isJavaIdentifier(pname)) pname = "arg" + i;
                    params.add(new Parameter(i, pname, sig.params().get(i), paramAnnotations.getOrDefault(i, List.of())));
                    slot += i < argTypes.length ? argTypes[i].getSize() : 1;
                }
                methods.add(new MethodTarget(binaryName, name, descriptor, access, sig.typeParams(), params,
                        sig.returnType(), exceptions, annotations, firstLine));
            }
        }
    }

    private static boolean isJavaIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return true;
    }

    /** Turns ASM annotation callbacks into an AnnotationInfo. Nested annotations are flattened to their class name. */
    private static final class AnnotationCollector extends AnnotationVisitor {
        private final String binaryName;
        private final boolean visible;
        private final java.util.function.Consumer<AnnotationInfo> sink;
        private final Map<String, Object> values = new HashMap<>();

        AnnotationCollector(String descriptor, boolean visible, java.util.function.Consumer<AnnotationInfo> sink) {
            super(Opcodes.ASM9);
            this.binaryName = Type.getType(descriptor).getClassName();
            this.visible = visible;
            this.sink = sink;
        }

        @Override public void visit(String name, Object value) {
            values.put(name, value instanceof Type t ? t.getClassName() : value);
        }

        @Override public void visitEnum(String name, String descriptor, String value) {
            values.put(name, Type.getType(descriptor).getClassName() + "." + value);
        }

        @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            values.put(name, Type.getType(descriptor).getClassName());
            return null;
        }

        @Override public AnnotationVisitor visitArray(String name) {
            List<Object> list = new ArrayList<>();
            values.put(name, list);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String n, Object value) { list.add(value instanceof Type t ? t.getClassName() : value); }
                @Override public void visitEnum(String n, String descriptor, String value) { list.add(Type.getType(descriptor).getClassName() + "." + value); }
            };
        }

        @Override public void visitEnd() { sink.accept(new AnnotationInfo(binaryName, values, visible)); }
    }
}
