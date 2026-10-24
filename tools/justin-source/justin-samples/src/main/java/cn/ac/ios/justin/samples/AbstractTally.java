package cn.ac.ios.justin.samples;

/**
 * The logic sits in an abstract class and the concrete subclass adds almost nothing, as in the Abstract* classes
 * of Commons Collections. The instance methods here are reachable only through a SimpleTally receiver.
 */
public abstract class AbstractTally {
    private int total;
    private int count;

    protected abstract int weight();

    public int add(int amount) {
        if (amount < 0) throw new IllegalArgumentException("amount");
        total += amount * weight();
        count++;
        return total;
    }

    public double mean() {
        if (count == 0) return 0.0;
        return (double) total / count;
    }

    public static final class SimpleTally extends AbstractTally {
        private final int weight;
        public SimpleTally(int weight) { this.weight = weight; }
        @Override protected int weight() { return weight; }
    }
}
