package cn.ac.ios.justin.samples;

/** Static state that one test can leave behind for another: format() depends on the last setDefault(). */
public final class Registry {
    private static String defaultPrefix = "std";

    private Registry() {}

    public static void setDefault(String prefix) {
        if (prefix == null || prefix.isEmpty()) throw new IllegalArgumentException("prefix");
        defaultPrefix = prefix;
    }

    public static String format(int id) {
        return defaultPrefix + "-" + id;
    }
}
