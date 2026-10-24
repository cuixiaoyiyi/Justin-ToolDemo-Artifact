package cn.ac.ios.justin.samples;

import java.util.ArrayList;
import java.util.List;

/**
 * Branches that depend on the state of the receiver: what a method does is decided by what the constructor
 * and earlier calls left in the fields, not by the arguments alone.
 */
public class Meter {
    /** Not a compile-time constant: the values are in the class, not in the code that reads them. */
    private static final int[] STEPS = {10, 100, 1000};

    private final int limit;
    private int level;
    private boolean locked;
    private String label;
    private final int[] history = new int[4];
    private int count;
    private final List<String> notes = new ArrayList<>();

    public Meter(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }

    public Meter(int limit, String label) {
        this(limit);
        this.label = label;
    }

    public void add(int n) {
        if (locked) throw new IllegalStateException("locked");
        if (n < 0) throw new IllegalArgumentException("negative");
        level += n;
        if (count < history.length) history[count++] = n;
    }

    public void lock() { locked = true; }

    public void unlock() { locked = false; }

    public void rename(String label) { this.label = label; }

    public void note(String text) {
        if (text == null) throw new NullPointerException("text");
        notes.add(text);
    }

    public int level() { return level; }

    /** Every branch but the last needs a particular state. */
    public String status(int margin) {
        if (locked) return "locked";
        if (level + margin > limit) return "over";
        if (level == 0) return "empty";
        if (label != null && label.startsWith("x")) return "x" + margin;
        return "ok";
    }

    /** The second value added decides. */
    public int recent(int n) {
        if (count >= 2 && history[1] == n) return 1;
        return 0;
    }

    /** Depends on a static array. */
    public static int step(int i) {
        if (i < 0 || i >= STEPS.length) return -1;
        if (STEPS[i] == 100) return 1;
        return 0;
    }

    /** Depends on a collection the receiver holds. */
    public String firstNote(int min) {
        if (notes.isEmpty()) return null;
        if (notes.size() >= min && min > 1) return notes.get(min - 1);
        return notes.get(0);
    }
}
