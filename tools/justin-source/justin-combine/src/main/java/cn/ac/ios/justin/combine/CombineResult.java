package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.value.InputTuple;

import java.util.List;

/**
 * Tuples plus how well they cover the requested strength: coveredInteractions / totalInteractions
 * over the legal candidates (illegal tuples are extra and not counted).
 */
public record CombineResult(List<InputTuple> tuples, int strength, long totalInteractions, long coveredInteractions, int legalRows, int illegalRows) {
    public double coverage() { return totalInteractions == 0 ? 1.0 : (double) coveredInteractions / totalInteractions; }
}
