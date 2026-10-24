package cn.ac.ios.justin.samples;

/**
 * Public entry points whose interesting branches live in private helpers: their lines are only
 * reachable as symbolic-execution targets when calls into the same class are inlined.
 */
public final class Delegator {

    private final int base;

    public Delegator(int base) {
        if (base < 0) throw new IllegalArgumentException("base");
        this.base = base;
    }

    public static int classify(int code, String tag) {
        if (tag == null) throw new IllegalArgumentException("tag");
        return score(code) + weight(tag);
    }

    private static int score(int code) {
        if (code < 0) return -1;
        if (code == 42) return 100;
        if (code > 1000) return 2;
        return 1;
    }

    private static int weight(String tag) {
        if (tag.isEmpty()) return 0;
        if (tag.startsWith("hot")) return 10;
        if (tag.length() > 5) return 5;
        return 1;
    }

    public int shifted(int x) {
        return clampToBase(x) * 2;
    }

    private int clampToBase(int x) {
        if (x > base) return base;
        if (x < -base) return -base;
        return x;
    }
}
