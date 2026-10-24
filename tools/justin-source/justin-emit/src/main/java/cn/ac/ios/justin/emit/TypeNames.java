package cn.ac.ios.justin.emit;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import cn.ac.ios.justin.core.type.TypeRef.Wildcard;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;

import java.util.ArrayList;
import java.util.List;

/** TypeRef to JavaPoet TypeName; nested classes ('$') become ClassName.nestedClass chains. */
public final class TypeNames {
    private TypeNames() {}

    public static ClassName className(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        String pkg = dot < 0 ? "" : binaryName.substring(0, dot);
        String[] parts = binaryName.substring(dot + 1).split("\\$");
        ClassName cn = ClassName.get(pkg, parts[0]);
        for (int i = 1; i < parts.length; i++) cn = cn.nestedClass(parts[i]);
        return cn;
    }

    public static TypeName of(TypeRef t) {
        if (t instanceof Primitive p) {
            return switch (p.kind()) {
                case BOOLEAN -> TypeName.BOOLEAN; case BYTE -> TypeName.BYTE; case CHAR -> TypeName.CHAR;
                case SHORT -> TypeName.SHORT; case INT -> TypeName.INT; case LONG -> TypeName.LONG;
                case FLOAT -> TypeName.FLOAT; case DOUBLE -> TypeName.DOUBLE; case VOID -> TypeName.VOID;
            };
        }
        if (t instanceof ArrayRef a) return ArrayTypeName.of(of(a.component()));
        if (t instanceof ClassRef c) {
            ClassName raw = className(c.binaryName());
            if (c.typeArgs().isEmpty()) return raw;
            List<TypeName> args = new ArrayList<>();
            for (TypeRef a : c.typeArgs()) args.add(of(a).box());
            return ParameterizedTypeName.get(raw, args.toArray(new TypeName[0]));
        }
        if (t instanceof TypeVar v) return TypeVariableName.get(v.name());
        if (t instanceof Wildcard w) {
            if (w.upper() != null) return WildcardTypeName.subtypeOf(of(w.upper()).box());
            if (w.lower() != null) return WildcardTypeName.supertypeOf(of(w.lower()).box());
            return WildcardTypeName.subtypeOf(ClassName.OBJECT);
        }
        return ClassName.OBJECT;
    }

    /** Raw (erased) type name for casts and class literals. */
    public static TypeName raw(TypeRef t) {
        if (t instanceof ClassRef c) return className(c.binaryName());
        if (t instanceof ArrayRef a) return ArrayTypeName.of(raw(a.component()));
        if (t instanceof TypeVar v) return raw(v.firstBoundOrObject());
        if (t instanceof Wildcard w) return w.upper() != null ? raw(w.upper()) : ClassName.OBJECT;
        return of(t);
    }
}
