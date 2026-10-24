package cn.ac.ios.justin.analysis;

import cn.ac.ios.justin.core.test.BodyFacts;
import cn.ac.ios.justin.core.test.Relation;

import java.util.List;
import java.util.Set;

/**
 * Flow-insensitive facts about one method body: constants, branches, callees, explicit throws,
 * parameter relations, and two conservative properties (never returns null, pure).
 */
public record MethodFacts(
        String methodId,
        int stmtCount,
        int branchCount,
        List<Integer> branchLines,
        Set<Integer> intConstants,
        Set<Long> longConstants,
        Set<Double> doubleConstants,
        Set<String> stringConstants,
        Set<String> invokedMethods,
        Set<String> explicitThrows,
        boolean preconditionChecks,
        List<Relation> relations,
        boolean neverReturnsNull,
        boolean pure,
        boolean bodyAvailable) {

    public static MethodFacts unavailable(String id) {
        return new MethodFacts(id, 0, 0, List.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false, List.of(), false, false, false);
    }

    public BodyFacts toBodyFacts() {
        return new BodyFacts(explicitThrows, preconditionChecks, bodyAvailable, intConstants, longConstants, doubleConstants, stringConstants,
                relations, neverReturnsNull, pure);
    }
}
