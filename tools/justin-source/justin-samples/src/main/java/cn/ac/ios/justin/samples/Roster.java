package cn.ac.ios.justin.samples;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Branches that depend on collections: how many elements one has, what an iteration meets, whether a value is
 * in it, what a map holds for a key. Both as arguments and as state of the receiver.
 */
public class Roster {
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> scores = new HashMap<>();

    public void enroll(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name");
        if (names.contains(name)) throw new IllegalStateException("enrolled twice: " + name);
        names.add(name);
    }

    public void score(String name, int points) {
        if (!names.contains(name)) throw new IllegalArgumentException("not enrolled: " + name);
        scores.put(name, points);
    }

    public int enrolled() { return names.size(); }

    public String last() {
        if (names.isEmpty()) return null;
        return names.get(names.size() - 1);
    }

    public String rank(String name) {
        Integer s = scores.get(name);
        if (s == null) return names.contains(name) ? "unscored" : "unknown";
        if (s >= 90) return "top";
        return "ok";
    }

    public static String describe(List<String> items) {
        if (items == null) return "none";
        if (items.isEmpty()) return "empty";
        if (items.size() > 3) return "many";
        if (items.get(0).equals("first")) return "first";
        if (items.contains("needle")) return "found";
        return "some";
    }

    public static int price(Map<String, Integer> prices, String item) {
        Integer p = prices.get(item);
        if (p == null) return -1;
        if (p > 100) return 2;
        return 1;
    }

    public static int countLong(Collection<String> words) {
        int n = 0;
        for (String w : words) {
            if (w.length() > 5) n++;
        }
        if (n >= 2) return 2;
        return n;
    }

    public static boolean both(Set<Integer> ids) {
        if (ids.contains(7) && ids.contains(9)) return true;
        return false;
    }
}
