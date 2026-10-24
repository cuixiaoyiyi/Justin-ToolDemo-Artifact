package cn.ac.ios.justin.emit;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.ClassValue;
import cn.ac.ios.justin.core.value.Value.EnumValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.Value.StringValue;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import java.util.List;

/** Renders Values as Java expressions. Nulls are cast to their static type so overloads resolve. */
public final class ValueCode {
    private ValueCode() {}

    public static CodeBlock of(Value v) {
        if (v instanceof PrimitiveValue p) return primitive(p);
        if (v instanceof StringValue s) return string(s.value());
        if (v instanceof NullValue n) return CodeBlock.of("($T) null", TypeNames.raw(n.type()));
        if (v instanceof EnumValue e) return CodeBlock.of("$T.$L", TypeNames.className(e.enumClass()), e.constant());
        if (v instanceof ClassValue c) {
            // ArrayList.class is a Class<ArrayList>; where Class<ArrayList<Integer>> is expected only a raw Class converts
            if (c.literal() instanceof TypeRef.ClassRef cr && cr.isParameterized()) return CodeBlock.of("(($T) $T.class)", Class.class, TypeNames.raw(c.literal()));
            return CodeBlock.of("$T.class", TypeNames.raw(c.literal()));
        }
        if (v instanceof ArrayValue a) {
            // array creation must use the erasure: new List<String>[] is illegal Java
            CodeBlock.Builder b = CodeBlock.builder().add("new $T", TypeNames.raw(new TypeRef.ArrayRef(a.component())));
            b.add("{");
            b.add(args(a.elements()));
            b.add("}");
            return b.build();
        }
        if (v instanceof ObjectValue o) return recipe(o);
        if (v instanceof Value.VarRef ref) return CodeBlock.of("$L", ref.name());
        if (v instanceof Value.CastValue c) return CodeBlock.of("(($T) $L)", TypeNames.raw(c.to()), of(c.inner()));
        if (v instanceof Value.FunctionValue f) {
            StringBuilder params = new StringBuilder("(");
            for (int i = 0; i < f.arity(); i++) params.append(i > 0 ? ", " : "").append("p").append(i);
            params.append(")");
            // the cast gives the lambda its target type (with the bound type arguments) in any position
            return switch (f.behavior()) {
                case CONSTANT -> CodeBlock.of("(($T) $L -> $L)", TypeNames.of(f.iface()), params, of(f.constant()));
                case IDENTITY -> CodeBlock.of("(($T) $L -> p0)", TypeNames.of(f.iface()), params);
                case NOOP -> CodeBlock.of("(($T) $L -> { })", TypeNames.of(f.iface()), params);
            };
        }
        throw new IllegalArgumentException("unsupported value " + v);
    }

    /**
     * A string literal. A string with an unpaired surrogate (half of an emoji, as a substring-based method
     * may return) cannot be written to a UTF-8 source file as itself: the encoder replaces it by '?'. Such
     * strings are spelled with unicode escapes.
     */
    static CodeBlock string(String value) {
        boolean plain = true;
        for (int i = 0; i < value.length() && plain; i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) i++;
            else if (Character.isSurrogate(c)) plain = false;
        }
        if (plain) return CodeBlock.of("$S", value);
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c >= 32 && c < 127 && c != '"' && c != '\\') sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return CodeBlock.of("$L", sb.append('"').toString());
    }

    private static CodeBlock recipe(ObjectValue o) {
        Recipe r = o.recipe();
        if (r instanceof Recipe.Boxed b) {
            TypeName wrapper = TypeNames.raw(TypeRef.ClassRef.of(b.primitive().kind().boxed));
            return CodeBlock.of("$T.valueOf($L)", wrapper, primitive(b.primitive()));
        }
        if (r instanceof Recipe.ConstructorCall cc) {
            // explicit type arguments, never diamond: in argument position javac would have to infer
            // them from the callee's signature, which fails for recursively bounded variables
            return CodeBlock.of("new $T($L)", TypeNames.of(o.type()), args(cc.args()));
        }
        if (r instanceof Recipe.StaticCall sc) {
            return CodeBlock.of("$T.$L($L)", TypeNames.className(sc.className()), sc.methodName(), args(sc.args()));
        }
        if (r instanceof Recipe.InstanceCall ic) {
            return CodeBlock.of("$L.$L($L)", of(ic.target()), ic.methodName(), args(ic.args()));
        }
        if (r instanceof Recipe.Sequence seq) {
            // a sequence must be hoisted into statements by the oracle; rendering only the base here
            // would silently drop the mutators, so fail loudly instead
            throw new IllegalArgumentException("construction sequence not hoisted: " + o.type());
        }
        throw new IllegalArgumentException("unsupported recipe " + r);
    }

    public static CodeBlock args(List<Value> values) {
        CodeBlock.Builder b = CodeBlock.builder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.add(", ");
            b.add(of(values.get(i)));
        }
        return b.build();
    }

    public static CodeBlock primitive(PrimitiveValue p) {
        Object v = p.boxed();
        return switch (p.kind()) {
            case BOOLEAN -> CodeBlock.of("$L", v);
            case BYTE -> CodeBlock.of("(byte) $L", v);
            case SHORT -> CodeBlock.of("(short) $L", v);
            case CHAR -> charLiteral((Character) v);
            case INT -> intLiteral((Integer) v);
            case LONG -> longLiteral((Long) v);
            case FLOAT -> floatLiteral((Float) v);
            case DOUBLE -> doubleLiteral((Double) v);
            case VOID -> throw new IllegalArgumentException("void");
        };
    }

    private static CodeBlock intLiteral(int v) {
        if (v == Integer.MIN_VALUE) return CodeBlock.of("Integer.MIN_VALUE");
        if (v == Integer.MAX_VALUE) return CodeBlock.of("Integer.MAX_VALUE");
        return CodeBlock.of("$L", v);
    }

    private static CodeBlock longLiteral(long v) {
        if (v == Long.MIN_VALUE) return CodeBlock.of("Long.MIN_VALUE");
        if (v == Long.MAX_VALUE) return CodeBlock.of("Long.MAX_VALUE");
        return CodeBlock.of("$LL", v);
    }

    private static CodeBlock floatLiteral(float v) {
        if (Float.isNaN(v)) return CodeBlock.of("Float.NaN");
        if (v == Float.POSITIVE_INFINITY) return CodeBlock.of("Float.POSITIVE_INFINITY");
        if (v == Float.NEGATIVE_INFINITY) return CodeBlock.of("Float.NEGATIVE_INFINITY");
        return CodeBlock.of("$LF", Float.toString(v));
    }

    private static CodeBlock doubleLiteral(double v) {
        if (Double.isNaN(v)) return CodeBlock.of("Double.NaN");
        if (v == Double.POSITIVE_INFINITY) return CodeBlock.of("Double.POSITIVE_INFINITY");
        if (v == Double.NEGATIVE_INFINITY) return CodeBlock.of("Double.NEGATIVE_INFINITY");
        return CodeBlock.of("$LD", Double.toString(v));
    }

    private static CodeBlock charLiteral(char c) {
        String s = switch (c) {
            case '\'' -> "\\'"; case '\\' -> "\\\\"; case '\n' -> "\\n"; case '\r' -> "\\r"; case '\t' -> "\\t";
            case '\b' -> "\\b"; case '\f' -> "\\f"; case '\0' -> "\\0";
            default -> (c < 0x20 || c > 0x7e) ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
        return CodeBlock.of("'$L'", s);
    }
}
