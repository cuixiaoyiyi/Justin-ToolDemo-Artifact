package cn.ac.ios.justin.oracle;

import cn.ac.ios.justin.core.target.AnnotationInfo;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.BodyFacts;
import cn.ac.ios.justin.core.test.Outcome;
import cn.ac.ios.justin.core.test.Verdict;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;

import java.util.Set;

/**
 * Decides, with evidence, whether an observed exception is contract behaviour or a likely defect.
 * Rules are applied from most to least trustworthy; the first that fires wins.
 */
public final class ExceptionClassifier {

    /** Exception families a method typically throws on purpose to reject bad input or state. */
    private static final Set<String> CONTRACT_FAMILY = Set.of(
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.UnsupportedOperationException",
            "java.lang.IndexOutOfBoundsException", "java.util.NoSuchElementException", "java.lang.NumberFormatException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.util.MissingResourceException", "java.util.IllegalFormatException", "java.lang.NullPointerException");

    /** Exceptions the JVM raises implicitly; on legal input without an explicit throw they point at a defect. */
    private static final Set<String> IMPLICIT_FAMILY = Set.of(
            "java.lang.NullPointerException", "java.lang.ClassCastException", "java.lang.ArithmeticException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.NegativeArraySizeException", "java.lang.ArrayStoreException",
            "java.util.ConcurrentModificationException", "java.lang.IndexOutOfBoundsException");

    private static final Set<String> BOUNDS_FAMILY = Set.of(
            "java.lang.IndexOutOfBoundsException", "java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.NegativeArraySizeException");

    private ExceptionClassifier() {}

    public static Verdict classify(MethodTarget m, InputTuple tuple, Outcome.Threw threw, BodyFacts facts) {
        String ex = threw.exceptionClass();
        String simple = ex.substring(ex.lastIndexOf('.') + 1);

        // 1. the tuple deliberately violates a known constraint (annotation, declared invalid example)
        if (tuple.source().startsWith("illegal:")) {
            return Verdict.expected("input violates the contract of parameter '" + tuple.source().substring("illegal:".length()) + "'");
        }
        // 2. declared in the throws clause (exact or by simple name of a supertype we cannot resolve here)
        for (String d : m.thrownExceptions()) {
            if (d.equals(ex)) return Verdict.expected("declared in throws clause");
        }
        // 3. a null argument on a parameter annotated non-null
        boolean nullArg = tuple.args().stream().anyMatch(v -> v instanceof Value.NullValue);
        if (nullArg && ex.equals("java.lang.NullPointerException")) {
            for (int i = 0; i < tuple.args().size(); i++) {
                if (tuple.args().get(i) instanceof Value.NullValue && isNonNullAnnotated(m.params().get(i))) {
                    return Verdict.expected("null passed to @" + nonNullName(m.params().get(i)) + " parameter '" + m.params().get(i).name() + "'");
                }
            }
        }
        // 4. the body itself creates and throws this type
        if (facts.available() && facts.explicitThrows().contains(ex)) {
            return Verdict.expected("explicitly thrown in the method body");
        }
        // 5. thrown directly by the target frame and of a contract family
        if (threw.thrownDirectlyByTarget() && CONTRACT_FAMILY.contains(ex)) {
            return Verdict.expected(simple + " raised directly by the method (precondition check)");
        }
        // 6. precondition helpers (requireNonNull, Validate.*) in the body and a matching family
        if (facts.available() && facts.preconditionChecks() && (ex.equals("java.lang.NullPointerException") || ex.equals("java.lang.IllegalArgumentException"))) {
            return Verdict.expected(simple + " from a precondition helper called in the body");
        }
        // 7. null argument without any nullability contract: cannot tell
        if (nullArg && ex.equals("java.lang.NullPointerException")) {
            return Verdict.unknown("null argument, no nullability contract on the parameter");
        }
        // 7b. bounds exceptions when an index-like parameter exists: an out-of-range index is the usual cause.
        //     @IndexOf makes it a contract; a merely index-looking name leaves it undecided (P2 relations refine this)
        if (BOUNDS_FAMILY.contains(ex)) {
            for (Parameter p : m.params()) {
                if (!p.type().erasureName().equals("int") && !p.type().erasureName().equals("long")) continue;
                if (p.annotations().stream().anyMatch(a -> a.simpleName().equals("IndexOf"))) {
                    return Verdict.expected("index parameter '" + p.name() + "' is @IndexOf; out-of-range index");
                }
            }
            for (Parameter p : m.params()) {
                if (!p.type().erasureName().equals("int") && !p.type().erasureName().equals("long")) continue;
                String n = p.name().toLowerCase();
                if (n.contains("index") || n.contains("idx") || n.contains("pos") || n.contains("offset") || n.contains("start")
                        || n.contains("end") || n.equals("from") || n.equals("to") || n.contains("len") || n.contains("count") || n.contains("size") || n.equals("i") || n.equals("n")) {
                    return Verdict.unknown(simple + " with index-like parameter '" + p.name() + "' and no declared range");
                }
            }
        }
        // 8. implicit runtime exception on legal, non-null input with no explicit throw of that type
        if (IMPLICIT_FAMILY.contains(ex)) {
            String where = threw.thrownDirectlyByTarget() ? "inside the method" : "from a callee";
            String body = facts.available() ? "no explicit throw of this type in the body" : "body not analyzable";
            return Verdict.suspect(simple + " on legal input " + where + ", " + body);
        }
        // 9. anything else: checked/custom exceptions thrown by callees on legal input
        return Verdict.unknown(simple + (threw.thrownDirectlyByTarget() ? " raised by the method" : " propagated from a callee") + " on legal input");
    }

    private static boolean isNonNullAnnotated(Parameter p) { return nonNullName(p) != null; }

    private static String nonNullName(Parameter p) {
        for (AnnotationInfo a : p.annotations()) {
            String s = a.simpleName();
            if (s.equals("NotNull") || s.equals("NonNull") || s.equals("Nonnull") || s.equals("NotBlank") || s.equals("NotEmpty")) return s;
        }
        return null;
    }
}
