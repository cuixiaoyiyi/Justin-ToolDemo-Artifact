package cn.ac.ios.justin.samples;

/** Interface with two project implementations, so interface-typed parameters can be satisfied. */
public interface Shape {
    double area();

    enum Kind { CIRCLE, SQUARE, OTHER }

    final class Circle implements Shape {
        private final double r;
        public Circle(double r) { if (r < 0) throw new IllegalArgumentException("r"); this.r = r; }
        @Override public double area() { return Math.PI * r * r; }
        public Kind kind() { return Kind.CIRCLE; }
    }

    final class Square implements Shape {
        private final double side;
        public Square(double side) { this.side = side; }
        @Override public double area() { return side * side; }
        public Kind kind() { return Kind.SQUARE; }
    }

    static Shape larger(Shape a, Shape b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.area() >= b.area() ? a : b;
    }

    static String describe(Kind kind, Shape s) {
        switch (kind) {
            case CIRCLE: return "circle " + s.area();
            case SQUARE: return "square " + s.area();
            default: return "other";
        }
    }
}
