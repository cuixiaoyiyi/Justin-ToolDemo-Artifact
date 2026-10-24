package cn.ac.ios.justin.symexec.strings;

/**
 * The abstraction an API invocation contributes to the caller string (the paper's RegexWrapper
 * ⟨R, L, S, Lmin⟩): {@code r} describes the part of the caller the API skips or fixes, {@code l} the
 * admissible length of what follows (-1: no bound), {@code lmin} the minimum caller length.
 *
 * <p>Two departures from the paper's tuple. What may follow the consumed part is explicit
 * ({@code post}): substring(i1, i2) leaves the caller free after i2, which Concat(Rpre, R) alone does
 * not say. The replacement regex S of append(x) is not a field: builder contents are sliced into a
 * concatenation term and the appended literal is removed by a right quotient, which is exact where S
 * only restricts the wildcard suffix.
 */
public record RegexWrapper(Rx r, int l, Rx post, int lmin) {

    public static RegexWrapper terminated(Rx r) { return new RegexWrapper(r, -1, null, r.minLength()); }

    /**
     * Merge (Algorithm 3): lifts the abstraction {@code cur} of an API's result to the API's caller.
     * Returns null when the result cannot have the length the API allows.
     */
    public static RegexWrapper merge(RegexWrapper pre, RegexWrapper cur) {
        Rx r = cur.r();
        if (pre.l() != -1) {
            if (pre.l() - cur.lmin() < 0) return null;
            r = r.bound(pre.l());
            if (r == null) return null;
        }
        Rx whole = pre.r().concat(r);
        if (pre.post() != null) whole = whole.concat(pre.post());
        return new RegexWrapper(whole, -1, null, whole.minLength());
    }
}
