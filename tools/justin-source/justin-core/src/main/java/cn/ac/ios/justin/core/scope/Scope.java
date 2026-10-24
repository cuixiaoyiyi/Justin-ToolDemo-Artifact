package cn.ac.ios.justin.core.scope;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What to generate tests for. Exactly one selector kind applies; include/exclude globs refine it.
 * Method selectors look like pkg.Cls#name or pkg.Cls#name(int,java.lang.String).
 */
public record Scope(Kind kind, String value, boolean recursive, List<String> includes, List<String> excludes) {

    public enum Kind { PROJECT, MODULE, PACKAGE, CLASS, METHOD }

    public Scope {
        includes = includes == null ? List.of() : List.copyOf(includes);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }

    public static Scope project() { return new Scope(Kind.PROJECT, null, true, List.of(), List.of()); }
    public static Scope module(String name) { return new Scope(Kind.MODULE, name, true, List.of(), List.of()); }
    public static Scope pkg(String name, boolean recursive) { return new Scope(Kind.PACKAGE, name, recursive, List.of(), List.of()); }
    public static Scope cls(String binaryName) { return new Scope(Kind.CLASS, binaryName, false, List.of(), List.of()); }
    public static Scope method(String spec) { return new Scope(Kind.METHOD, spec, false, List.of(), List.of()); }

    public Scope withFilters(List<String> inc, List<String> exc) { return new Scope(kind, value, recursive, inc, exc); }

    public String className() {
        if (kind == Kind.CLASS) return value;
        if (kind == Kind.METHOD) return value.substring(0, value.indexOf('#'));
        return null;
    }

    /** Whether the class is inside the selector, before include/exclude filtering. */
    public boolean coversClass(String binaryName) {
        return switch (kind) {
            case PROJECT, MODULE -> true;
            case PACKAGE -> {
                String pkg = packageOf(binaryName);
                yield recursive ? (pkg.equals(value) || pkg.startsWith(value + ".")) : pkg.equals(value);
            }
            case CLASS -> binaryName.equals(value) || binaryName.startsWith(value + "$");
            case METHOD -> binaryName.equals(className());
        };
    }

    public boolean coversMethod(MethodTarget m) {
        if (kind != Kind.METHOD) return true;
        String spec = value.substring(value.indexOf('#') + 1);
        int paren = spec.indexOf('(');
        String name = paren < 0 ? spec : spec.substring(0, paren);
        if (!m.name().equals(name) && !(m.isConstructor() && name.equals(m.simpleClassName()))) return false;
        if (paren < 0) return true;
        String wanted = spec.substring(paren + 1, spec.lastIndexOf(')')).replace(" ", "");
        String actual = m.params().stream().map(p -> p.type().erasureName()).reduce((a, b) -> a + "," + b).orElse("");
        return wanted.equals(actual);
    }

    public boolean passesFilters(String binaryName) {
        if (!includes.isEmpty() && includes.stream().noneMatch(g -> glob(g).matcher(binaryName).matches())) return false;
        return excludes.stream().noneMatch(g -> glob(g).matcher(binaryName).matches());
    }

    public boolean accepts(ClassTarget c) { return coversClass(c.binaryName()) && passesFilters(c.binaryName()); }

    static String packageOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i < 0 ? "" : binaryName.substring(0, i);
    }

    /** "**" matches across packages, "*" within one segment. */
    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') { sb.append(".*"); i++; }
                else sb.append("[^.]*");
            } else if (c == '.') sb.append("\\.");
            else if (c == '$') sb.append("\\$");
            else sb.append(c);
        }
        return Pattern.compile(sb.toString());
    }
}
