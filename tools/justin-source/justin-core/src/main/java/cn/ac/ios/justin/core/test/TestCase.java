package cn.ac.ios.justin.core.test;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.value.InputTuple;

import java.util.List;

/**
 * A generated test: a statement sequence plus provenance. name must be a valid Java identifier and
 * unique within its test class. verdict is non-null only for exception tests.
 */
public record TestCase(
        String name,
        MethodTarget target,
        InputTuple input,
        Outcome outcome,
        List<Statement> statements,
        String strategy,
        long seed,
        List<String> notes,
        Verdict verdict) {

    public TestCase {
        statements = List.copyOf(statements);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public TestCase(String name, MethodTarget target, InputTuple input, Outcome outcome, List<Statement> statements,
                    String strategy, long seed, List<String> notes) {
        this(name, target, input, outcome, statements, strategy, seed, notes, null);
    }

    public int assertionCount() {
        return (int) statements.stream().filter(s -> s instanceof Statement.Assert).count();
    }

    public boolean isSuspect() { return verdict != null && verdict.kind() == Verdict.Kind.SUSPECT; }
}
