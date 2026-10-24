package cn.ac.ios.justin.samples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A generic container whose interesting branches need state (empty, one element, several) and
 * function-typed arguments that agree with the container's element type.
 */
public class Pipeline<T> {

    /** Project-defined single-method interface, as libraries had before java.util.function. */
    public interface Transformer<I, O> {
        O transform(I input);
    }

    private final List<T> items = new ArrayList<>();

    public Pipeline<T> add(T item) {
        if (item == null) throw new IllegalArgumentException("null item");
        items.add(item);
        return this;
    }

    public int size() { return items.size(); }

    public T first() {
        if (items.isEmpty()) throw new IllegalStateException("empty pipeline");
        return items.get(0);
    }

    public <R> List<R> map(Function<? super T, ? extends R> f) {
        List<R> out = new ArrayList<>();
        for (T t : items) out.add(f.apply(t));
        return out;
    }

    public List<T> filter(Predicate<T> p) {
        List<T> out = new ArrayList<>();
        for (T t : items) if (p.test(t)) out.add(t);
        return out;
    }

    public T max(Comparator<T> order) {
        if (items.isEmpty()) throw new IllegalStateException("empty pipeline");
        T best = items.get(0);
        for (T t : items) if (order.compare(t, best) > 0) best = t;
        return best;
    }

    public String describe(Transformer<T, String> label) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (T t : items) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(label.transform(t));
        }
        return sb.append(']').toString();
    }

    /** Arrays behave like containers: empty, one element, several, and a hole. */
    public static int countNonNull(String[] values) {
        if (values.length == 0) return -1;
        int n = 0;
        for (String v : values) if (v != null) n++;
        return n == values.length ? n : -n;
    }
}
