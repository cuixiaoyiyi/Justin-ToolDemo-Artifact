package cn.ac.ios.justin.core.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Candidate values for one slot (a parameter or the receiver). Insertion ordered, duplicate values collapsed. */
public final class ValuePool {
    private final int slot;
    private final LinkedHashSet<Value> seen = new LinkedHashSet<>();
    private final List<Candidate> candidates = new ArrayList<>();

    public ValuePool(int slot) { this.slot = slot; }

    /** Slot index: -1 for the receiver, otherwise the parameter index. */
    public int slot() { return slot; }

    public boolean add(Candidate c) {
        if (!seen.add(c.value())) return false;
        candidates.add(c);
        return true;
    }

    public void addAll(Iterable<Candidate> cs) { for (Candidate c : cs) add(c); }

    public List<Candidate> candidates() { return Collections.unmodifiableList(candidates); }
    public boolean isEmpty() { return candidates.isEmpty(); }
    public int size() { return candidates.size(); }
}
