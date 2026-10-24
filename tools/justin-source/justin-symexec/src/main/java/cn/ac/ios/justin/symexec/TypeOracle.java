package cn.ac.ios.justin.symexec;

import sootup.core.model.Body;
import sootup.core.signatures.MethodSignature;

import java.util.List;
import java.util.Optional;

/**
 * What the executor asks about classes: who is a subtype of whom, which classes implement an abstract type,
 * and which body a virtual call runs on an object of a given class.
 */
public interface TypeOracle {

    /** Whether every instance of sub is an instance of sup; null when one of the two is not known. */
    Boolean subtype(String sub, String sup);

    /** True for an interface; false for a class or when the type is not known. */
    boolean isInterface(String type);

    /** True when objects of exactly this class can exist (a class that is not abstract). */
    boolean instantiable(String type);

    /** Classes with instances that are subtypes of the type, the ones of the project first; the type itself is not among them. */
    List<String> implementations(String type);

    /** The body a call of the method runs on an object of exactly this class: the one the class declares or inherits. */
    Optional<Body> body(String exactClass, MethodSignature declared);

    /** Knows the classes of the running JDK by reflection and nothing else. */
    TypeOracle JDK = new TypeOracle() {
        @Override public Boolean subtype(String sub, String sup) {
            if (sub.equals(sup) || sup.equals("java.lang.Object")) return true;
            Class<?> a = load(sub), b = load(sup);
            return a == null || b == null ? null : b.isAssignableFrom(a);
        }
        @Override public boolean isInterface(String type) { Class<?> c = load(type); return c != null && c.isInterface(); }
        @Override public boolean instantiable(String type) {
            Class<?> c = load(type);
            return c != null && !c.isInterface() && !java.lang.reflect.Modifier.isAbstract(c.getModifiers());
        }
        @Override public List<String> implementations(String type) { return List.of(); }
        @Override public Optional<Body> body(String exactClass, MethodSignature declared) { return Optional.empty(); }
    };

    /** A class of the JDK, not initialized; null for anything else. */
    static Class<?> load(String name) {
        if (!name.startsWith("java.") && !name.startsWith("javax.")) return null;
        try {
            return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
