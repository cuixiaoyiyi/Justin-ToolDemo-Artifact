package cn.ac.ios.justin.generics;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Kind;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import cn.ac.ios.justin.core.type.TypeRef.Wildcard;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for JVM descriptors and generic Signature attributes (JVMS 4.7.9.1).
 * Produces TypeRef trees. Type variables inside a signature are emitted as TypeVar references with
 * empty bounds; the declaring formal parameter (with bounds) is returned separately.
 */
public final class SignatureParser {

    public record ClassSig(List<TypeVar> typeParams, TypeRef superClass, List<TypeRef> interfaces) {}
    public record MethodSig(List<TypeVar> typeParams, List<TypeRef> params, TypeRef returnType, List<TypeRef> throwsTypes) {}

    private final String s;
    private int pos;
    /** Type variables declared in this signature (or by the enclosing class), so references carry bounds. */
    private final java.util.Map<String, TypeVar> declared = new java.util.HashMap<>();

    private SignatureParser(String s) { this(s, List.of()); }

    private SignatureParser(String s, List<TypeVar> outer) {
        this.s = s;
        for (TypeVar v : outer) declared.put(v.name(), v);
    }

    // ---------- public entry points ----------

    /** Parses a field descriptor or a field type signature. */
    public static TypeRef parseType(String descriptorOrSignature) {
        SignatureParser p = new SignatureParser(descriptorOrSignature);
        TypeRef t = p.javaTypeSignature();
        p.expectEnd();
        return t;
    }

    /** Parses a method descriptor (no generics) e.g. (ILjava/lang/String;)V. */
    public static MethodSig parseMethodDescriptor(String descriptor) {
        SignatureParser p = new SignatureParser(descriptor);
        p.expect('(');
        List<TypeRef> params = new ArrayList<>();
        while (p.peek() != ')') params.add(p.javaTypeSignature());
        p.expect(')');
        TypeRef ret = p.javaTypeSignature();
        p.expectEnd();
        return new MethodSig(List.of(), params, ret, List.of());
    }

    /** Parses a method Signature attribute, falling back to the descriptor if signature is null. */
    public static MethodSig parseMethodSignature(String signature, String descriptor) {
        return parseMethodSignature(signature, descriptor, List.of());
    }

    /** Same, with the enclosing class's type parameters so that references to them carry their bounds. */
    public static MethodSig parseMethodSignature(String signature, String descriptor, List<TypeVar> classTypeParams) {
        if (signature == null) return parseMethodDescriptor(descriptor);
        try {
            SignatureParser p = new SignatureParser(signature, classTypeParams);
            List<TypeVar> tps = p.peek() == '<' ? p.typeParams() : List.of();
            p.expect('(');
            List<TypeRef> params = new ArrayList<>();
            while (p.peek() != ')') params.add(p.javaTypeSignature());
            p.expect(')');
            TypeRef ret = p.javaTypeSignature();
            List<TypeRef> throwsTypes = new ArrayList<>();
            while (p.pos < p.s.length() && p.peek() == '^') { p.pos++; throwsTypes.add(p.javaTypeSignature()); }
            p.expectEnd();
            MethodSig sig = new MethodSig(tps, params, ret, throwsTypes);
            // Signature may omit synthetic/mandated parameters (inner-class outer refs, enum ctor args):
            // if arity disagrees with the descriptor, trust the descriptor and pad from the left.
            MethodSig desc = parseMethodDescriptor(descriptor);
            if (desc.params().size() != params.size()) {
                int missing = desc.params().size() - params.size();
                if (missing > 0) {
                    List<TypeRef> padded = new ArrayList<>(desc.params().subList(0, missing));
                    padded.addAll(params);
                    return new MethodSig(tps, padded, ret, throwsTypes);
                }
                return desc;
            }
            return sig;
        } catch (RuntimeException e) {
            return parseMethodDescriptor(descriptor);
        }
    }

    /** Parses a class Signature attribute; superName/interfaces (internal names) are the fallback. */
    public static ClassSig parseClassSignature(String signature, String superName, String[] interfaces) {
        if (signature != null) {
            try {
                SignatureParser p = new SignatureParser(signature);
                List<TypeVar> tps = p.peek() == '<' ? p.typeParams() : List.of();
                TypeRef sup = p.classTypeSignature();
                List<TypeRef> ifs = new ArrayList<>();
                while (p.pos < p.s.length()) ifs.add(p.classTypeSignature());
                return new ClassSig(tps, sup, ifs);
            } catch (RuntimeException ignored) {
                // fall through to descriptor-level info
            }
        }
        TypeRef sup = superName == null ? null : ClassRef.of(superName.replace('/', '.'));
        List<TypeRef> ifs = new ArrayList<>();
        if (interfaces != null) for (String i : interfaces) ifs.add(ClassRef.of(i.replace('/', '.')));
        return new ClassSig(List.of(), sup, ifs);
    }

    // ---------- grammar ----------

    private List<TypeVar> typeParams() {
        expect('<');
        List<TypeVar> out = new ArrayList<>();
        while (peek() != '>') {
            String name = identifier(':');
            expect(':');
            List<TypeRef> bounds = new ArrayList<>();
            if (peek() != ':') bounds.add(referenceTypeSignature()); // class bound may be absent
            while (peek() == ':') { pos++; bounds.add(referenceTypeSignature()); }
            TypeVar v = new TypeVar(name, bounds);
            declared.put(name, v);
            out.add(v);
        }
        expect('>');
        return out;
    }

    private TypeRef javaTypeSignature() {
        char c = peek();
        switch (c) {
            case 'Z': case 'B': case 'C': case 'S': case 'I': case 'J': case 'F': case 'D': case 'V':
                pos++;
                return new Primitive(Kind.fromDescriptor(c));
            default:
                return referenceTypeSignature();
        }
    }

    private TypeRef referenceTypeSignature() {
        char c = peek();
        switch (c) {
            case 'L': return classTypeSignature();
            case 'T': {
                pos++;
                String name = identifier(';');
                expect(';');
                TypeVar d = declared.get(name);
                return d != null ? d : TypeVar.of(name);
            }
            case '[': {
                pos++;
                return new ArrayRef(javaTypeSignature());
            }
            default: throw error("reference type expected");
        }
    }

    private TypeRef classTypeSignature() {
        expect('L');
        StringBuilder name = new StringBuilder();
        List<TypeRef> args = List.of();
        while (true) {
            String id = identifierUntil("<.;");
            name.append(id);
            if (peek() == '<') {
                args = typeArguments();
            }
            if (peek() == '.') {
                pos++;
                name.append('$');
                args = List.of(); // inner class type args replace outer ones (outer args are dropped)
                continue;
            }
            expect(';');
            break;
        }
        return new ClassRef(name.toString().replace('/', '.'), args);
    }

    private List<TypeRef> typeArguments() {
        expect('<');
        List<TypeRef> out = new ArrayList<>();
        while (peek() != '>') {
            char c = peek();
            if (c == '*') { pos++; out.add(Wildcard.UNBOUNDED); }
            else if (c == '+') { pos++; out.add(Wildcard.extendsOf(referenceTypeSignature())); }
            else if (c == '-') { pos++; out.add(Wildcard.superOf(referenceTypeSignature())); }
            else out.add(referenceTypeSignature());
        }
        expect('>');
        return out;
    }

    // ---------- lexing helpers ----------

    private char peek() {
        if (pos >= s.length()) throw error("unexpected end");
        return s.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        pos++;
    }

    private void expectEnd() {
        if (pos != s.length()) throw error("trailing characters");
    }

    private String identifier(char terminator) {
        int start = pos;
        while (pos < s.length() && s.charAt(pos) != terminator) pos++;
        if (start == pos) throw error("identifier expected");
        return s.substring(start, pos);
    }

    private String identifierUntil(String terminators) {
        int start = pos;
        while (pos < s.length() && terminators.indexOf(s.charAt(pos)) < 0) pos++;
        if (start == pos) throw error("identifier expected");
        return s.substring(start, pos);
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at " + pos + " in '" + s + "'");
    }
}
