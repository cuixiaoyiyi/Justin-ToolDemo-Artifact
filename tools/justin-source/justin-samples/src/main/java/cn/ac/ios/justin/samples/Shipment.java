package cn.ac.ios.justin.samples;

import java.util.List;
import java.util.TreeSet;

/**
 * Branches that depend on the state of an argument, on the state of the object the receiver was built from, on
 * exceptions that are caught, and on sorted collections and removals.
 */
public class Shipment {

    public static class Box {
        private int weight;
        private boolean sealed;

        public Box(int weight) {
            if (weight < 0) throw new IllegalArgumentException("weight");
            this.weight = weight;
        }

        public void seal() { sealed = true; }

        public void load(int more) {
            if (sealed) throw new IllegalStateException("sealed");
            if (more < 0) throw new IllegalArgumentException("more");
            weight += more;
        }

        public int weight() { return weight; }
    }

    private final Box box;
    private int stamps;

    public Shipment(Box box) {
        if (box == null) throw new NullPointerException("box");
        this.box = box;
    }

    public void stamp() { stamps++; }

    /** Depends on the state of the argument. */
    public static String accept(Box b) {
        if (b == null) return "none";
        if (b.sealed) return "sealed";
        if (b.weight > 100) return "heavy";
        return "open";
    }

    /** Depends on the state of the object the receiver was built from, and on the receiver's own. */
    public String label() {
        if (box.sealed && stamps > 0) return "ready";
        if (box.sealed) return "unstamped";
        return "open";
    }

    /** Exceptions of a call are handled here. */
    public String checked(int more) {
        try {
            box.load(more);
        } catch (IllegalStateException e) {
            return "sealed";
        } catch (IllegalArgumentException e) {
            return "bad";
        }
        return "loaded";
    }

    /** Exceptions of an array access are handled here. */
    public static String guarded(int[] values, int i) {
        try {
            int v = values[i];
            if (v > 10) return "big";
            return "small";
        } catch (ArrayIndexOutOfBoundsException e) {
            return "out";
        } catch (NullPointerException e) {
            return "none";
        }
    }

    /** A sorted set: its first element is its smallest. */
    public static String first(TreeSet<String> names) {
        if (names.isEmpty()) return "-";
        String f = names.first();
        if (f.startsWith("a")) return "a-first";
        return "other";
    }

    /** Removal shifts the elements. */
    public static int drop(List<Integer> xs, int i) {
        xs.remove(i);
        if (xs.size() == 2 && xs.get(1) == 7) return 1;
        return 0;
    }
}
