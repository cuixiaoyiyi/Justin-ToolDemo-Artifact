package cn.ac.ios.justin.core.test;

import cn.ac.ios.justin.core.value.Value;

/** What happened when an input tuple was executed in the sandbox. */
public sealed interface Outcome permits Outcome.Returned, Outcome.Threw, Outcome.TimedOut, Outcome.Blocked, Outcome.SetupFailed {

    /** Normal return. value is null for void methods; Observed describes the returned object. */
    record Returned(Observed value) implements Outcome {}

    /** The target threw. exceptionClass is a binary name; declared says whether it is a checked exception in the throws clause. */
    record Threw(String exceptionClass, String message, boolean thrownDirectlyByTarget) implements Outcome {}

    record TimedOut(long millis) implements Outcome {}

    /** Sandbox refused something the tuple tried to do (IO, exit, threads). */
    record Blocked(String reason) implements Outcome {}

    /** Receiver or argument construction failed before the target was reached. */
    record SetupFailed(String exceptionClass, String message) implements Outcome {}

    /**
     * A snapshot of a runtime object reduced to something the oracle can assert on.
     * literal: the value is representable as a Java literal (primitive, String, null, enum, array of those).
     */
    sealed interface Observed permits Observed.Literal, Observed.Opaque, Observed.Collection, Observed.BigString {
        record Literal(Value value) implements Observed {}
        /** A string too long to embed as a literal: assert on its length and a short prefix instead. */
        record BigString(int length, String head) implements Observed {}
        /** Object we cannot fully represent; className plus optional toString for diagnostics only. */
        record Opaque(String className, String toStringOrNull) implements Observed {}
        /** List/Set/array-like content whose elements are observable. ordered=false for sets. */
        record Collection(String className, java.util.List<Observed> elements, boolean ordered) implements Observed {}
    }
}
