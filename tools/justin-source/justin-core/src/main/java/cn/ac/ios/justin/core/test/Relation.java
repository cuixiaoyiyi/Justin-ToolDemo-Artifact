package cn.ac.ios.justin.core.test;

/**
 * A relation between two parameters of a method, discovered by analysis or declared by annotation.
 * dependent is derived from base when tuples are built; source says where the relation came from.
 *
 * <ul>
 *   <li>INDEX_OF: 0 &lt;= dependent &lt; length(base) (array, String, CharSequence, List, Collection)</li>
 *   <li>LENGTH_LE: 0 &lt;= dependent &lt;= length(base)</li>
 *   <li>RANGE: base &lt;= dependent (from/to style int pairs)</li>
 *   <li>RANGE_BELOW: dependent &lt;= base (the same pair when the upper end is derived elsewhere)</li>
 *   <li>KEY_OF: dependent is a key present in base (Map)</li>
 *   <li>SAME_LENGTH: length(dependent) == length(base) (arrays)</li>
 *   <li>LENGTH_EQ: dependent == length(base) (@ArrayPara flag 0)</li>
 *   <li>LENGTH_GE: dependent &gt;= length(base) (@ArrayPara flag 1, a capacity)</li>
 * </ul>
 */
public record Relation(Kind kind, int dependent, int base, String source) {
    public enum Kind { INDEX_OF, LENGTH_LE, RANGE, RANGE_BELOW, KEY_OF, SAME_LENGTH, LENGTH_EQ, LENGTH_GE }
}
