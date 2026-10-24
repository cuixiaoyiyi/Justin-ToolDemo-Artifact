package cn.ac.ios.justin.samples;

import cn.ac.ios.justin.annotations.IndexOf;
import cn.ac.ios.justin.annotations.NonNull;

import java.util.List;

/** Array/index relations between parameters. */
public final class Matrix {

    public static int at(@NonNull int[] row, @IndexOf("row") int i) {
        return row[i];
    }

    public static int sumRange(int[] values, int from, int to) {
        if (from < 0 || to > values.length || from > to) throw new IndexOutOfBoundsException(from + ".." + to);
        int s = 0;
        for (int k = from; k < to; k++) s += values[k];
        return s;
    }

    public static <T extends Comparable<T>> T maxOf(List<T> items, @IndexOf("items") int startAt) {
        T best = items.get(startAt);
        for (int k = startAt + 1; k < items.size(); k++) {
            if (items.get(k).compareTo(best) > 0) best = items.get(k);
        }
        return best;
    }

    public static char charAt(String s, @IndexOf("s") int idx) {
        return s.charAt(idx);
    }

    public static int clamp(int value, int lo, int hi) {
        if (lo > hi) throw new IllegalArgumentException("lo > hi");
        return value < lo ? lo : (value > hi ? hi : value);
    }

    public static boolean sameLength(int[] a, int[] b) {
        if (a.length != b.length) return false;
        for (int k = 0; k < a.length; k++) if (a[k] != b[k]) return false;
        return true;
    }
}
