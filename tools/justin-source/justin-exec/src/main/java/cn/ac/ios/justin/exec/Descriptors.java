package cn.ac.ios.justin.exec;

import java.util.ArrayList;
import java.util.List;

/** Minimal JVM descriptor decoding to Java type names (no generics). */
public final class Descriptors {
    private Descriptors() {}

    public static List<String> parameterTypeNames(String descriptor) {
        List<String> out = new ArrayList<>();
        int i = 1; // skip '('
        while (descriptor.charAt(i) != ')') {
            int start = i;
            while (descriptor.charAt(i) == '[') i++;
            char c = descriptor.charAt(i);
            if (c == 'L') i = descriptor.indexOf(';', i) + 1; else i++;
            out.add(typeName(descriptor.substring(start, i)));
        }
        return out;
    }

    public static String returnTypeName(String descriptor) {
        return typeName(descriptor.substring(descriptor.indexOf(')') + 1));
    }

    public static String typeName(String d) {
        int dims = 0;
        while (d.charAt(dims) == '[') dims++;
        String base = switch (d.charAt(dims)) {
            case 'Z' -> "boolean"; case 'B' -> "byte"; case 'C' -> "char"; case 'S' -> "short";
            case 'I' -> "int"; case 'J' -> "long"; case 'F' -> "float"; case 'D' -> "double"; case 'V' -> "void";
            case 'L' -> d.substring(dims + 1, d.length() - 1).replace('/', '.');
            default -> throw new IllegalArgumentException("bad descriptor " + d);
        };
        return base + "[]".repeat(dims);
    }
}
