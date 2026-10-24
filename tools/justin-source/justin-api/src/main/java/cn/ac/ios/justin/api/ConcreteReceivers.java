package cn.ac.ios.justin.api;

import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.exec.Invoker;
import cn.ac.ios.justin.symexec.ReceiverStates;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate receivers of a method, built in the sandbox, and the values of their fields.
 *
 * <p>Fields are read by reflection while tests are generated, to find out which path a receiver takes. Nothing
 * is written, and nothing of this reaches the tests: they build the receiver by its recipe and call public
 * methods. A field that cannot be read (a JDK class that does not open its package) is reported as unknown and
 * stays a free symbol.
 */
final class ConcreteReceivers implements ReceiverStates {
    /** Elements of a collection that are looked at; a longer collection is reported by its size only. */
    private static final int MAX_ELEMENTS = 64;

    private final Invoker invoker;
    /** Candidate values by root: "" the receiver, arg:i the arguments of the method, ctor:i those of its constructor. */
    private final Map<String, List<Value>> roots;
    private final Map<String, Object[]> objects = new HashMap<>();
    private final Map<String, Object> cache = new HashMap<>();

    ConcreteReceivers(Invoker invoker, Map<String, List<Value>> roots) {
        this.invoker = invoker;
        Map<String, List<Value>> copy = new HashMap<>();
        roots.forEach((r, vs) -> copy.put(r, List.copyOf(vs)));
        this.roots = copy;
    }

    ConcreteReceivers(Invoker invoker, List<Value> receivers) { this(invoker, Map.of("", receivers)); }

    List<Value> values() { return roots.getOrDefault("", List.of()); }

    @Override public int candidates() { return candidates(""); }

    @Override public int candidates(String root) { return roots.getOrDefault(root, List.of()).size(); }

    @Override public Object read(int k, List<String> path) { return read("", k, path); }

    @Override
    public Object read(String root, int k, List<String> path) {
        if (path.isEmpty()) return readInstance(root, k, List.of());   // the candidate itself: its class
        boolean isStatic = path.get(0).startsWith("static:");
        String key = (isStatic ? "" : root + "/" + k + ":") + String.join("/", path);
        if (cache.containsKey(key)) return cache.get(key);
        Object v = isStatic ? readStatic(path) : readInstance(root, k, path);
        cache.put(key, v);
        return v;
    }

    private Object readInstance(String root, int k, List<String> path) {
        List<Value> values = roots.getOrDefault(root, List.of());
        if (k < 0 || k >= values.size()) return UNKNOWN;
        Object[] built = objects.computeIfAbsent(root, r -> new Object[values.size()]);
        if (built[k] == null) {
            Value v = values.get(k);
            Object o = invoker.inspect((sandbox, materializer) -> materializer.materialize(v));
            built[k] = o == null ? UNKNOWN : o;
        }
        if (built[k] == UNKNOWN) return UNKNOWN;
        if (path.isEmpty()) return new Ref(built[k].getClass().getName());
        return follow(built[k], path, 0);
    }

    private Object readStatic(List<String> path) {
        String first = path.get(0).substring("static:".length());
        int hash = first.indexOf('#');
        if (hash < 0) return UNKNOWN;
        String cls = first.substring(0, hash), name = first.substring(hash + 1);
        // initializing the class runs its static initializer: in the sandbox, on the invocation thread
        Object[] holder = invoker.inspect((sandbox, materializer) -> {
            Class<?> c = Class.forName(cls, true, sandbox.classLoader());
            Field f = c.getDeclaredField(name);
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) return null;
            f.setAccessible(true);
            return new Object[] {f.get(null)};
        });
        if (holder == null) return UNKNOWN;
        if (path.size() == 1) return report(holder[0]);
        if (holder[0] == null) return BROKEN;
        return follow(holder[0], path, 1);
    }

    /** Reading a field runs no code of the subject, so this happens on the caller's thread. */
    private static Object follow(Object start, List<String> path, int from) {
        Object cur = start;
        for (int i = from; i < path.size(); i++) {
            String seg = path.get(i);
            int hash = seg.indexOf('#');
            if (hash < 0) return UNKNOWN;
            Field f = field(cur.getClass(), seg.substring(0, hash), seg.substring(hash + 1));
            if (f == null || Modifier.isStatic(f.getModifiers())) return UNKNOWN;
            Object v;
            try {
                f.setAccessible(true);
                v = f.get(cur);
            } catch (RuntimeException | IllegalAccessException | LinkageError e) {
                return UNKNOWN;
            }
            if (i == path.size() - 1) return report(v);
            if (v == null) return BROKEN;
            cur = v;
        }
        return UNKNOWN;
    }

    private static Field field(Class<?> of, String declaring, String name) {
        for (Class<?> c = of; c != null; c = c.getSuperclass()) {
            if (!c.getName().equals(declaring)) continue;
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException | LinkageError | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Object report(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Character || v instanceof Number && isBoxed(v)) return v;
        if (v instanceof StringBuilder || v instanceof StringBuffer) return new Ref(v.getClass().getName());
        Class<?> c = v.getClass();
        if (c.isArray()) {
            Class<?> e = c.getComponentType();
            return e.isPrimitive() || e == String.class ? v : new Ref(c.getName());
        }
        // collections of the JDK answer size() and iterate without running code of the subject
        if (c.getName().startsWith("java.util.") && (v instanceof Collection<?> || v instanceof Map<?, ?>)) {
            try {
                Collection<?> col = v instanceof Map<?, ?> m ? m.keySet() : (Collection<?>) v;
                int size = col.size();
                List<Object> elements = new ArrayList<>();
                if (size <= MAX_ELEMENTS) {
                    for (Object x : col) {
                        if (x == null || x instanceof String || x instanceof Boolean || x instanceof Character || x instanceof Number && isBoxed(x)) elements.add(x);
                        else { elements = null; break; }
                    }
                } else {
                    elements = null;
                }
                return new Elements(c.getName(), size, elements);
            } catch (RuntimeException e) {
                return new Ref(c.getName());
            }
        }
        return new Ref(c.getName());
    }

    private static boolean isBoxed(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof Double || v instanceof Float;
    }
}
