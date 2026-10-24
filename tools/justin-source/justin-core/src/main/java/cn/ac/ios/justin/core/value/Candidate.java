package cn.ac.ios.justin.core.value;

/**
 * One candidate value for a parameter, tagged with the strategy that produced it and whether it is
 * expected to satisfy the parameter's constraints (legal) or deliberately violate them.
 */
public record Candidate(Value value, String source, boolean legal, String note) {
    public static Candidate legal(Value v, String source) { return new Candidate(v, source, true, null); }
    public static Candidate illegal(Value v, String source, String note) { return new Candidate(v, source, false, note); }
}
