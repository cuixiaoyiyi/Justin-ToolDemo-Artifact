package cn.ac.ios.justin.core.test;

/**
 * Classification of an exception outcome: is the throw part of the method's contract or a likely
 * defect? Never certain; the evidence string says which rule fired so a reader can judge.
 */
public record Verdict(Kind kind, String evidence) {
    public enum Kind {
        /** Contract behaviour: declared, explicitly thrown, or provoked by an input that violates a known constraint. */
        EXPECTED,
        /** Implicit runtime exception on legal, non-null input with no explicit throw of that type in the body. */
        SUSPECT,
        /** Not enough evidence either way (e.g. null argument without a nullability contract). */
        UNKNOWN
    }

    public static Verdict expected(String why) { return new Verdict(Kind.EXPECTED, why); }
    public static Verdict suspect(String why) { return new Verdict(Kind.SUSPECT, why); }
    public static Verdict unknown(String why) { return new Verdict(Kind.UNKNOWN, why); }
}
