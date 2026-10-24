package cn.ac.ios.justin.samples;

/** A bean whose interesting behaviour needs setters or a factory: construction sequences. */
public final class Order {
    private int quantity;
    private double unitPrice;
    private String coupon;

    public Order() {}

    private Order(int quantity, double unitPrice) {
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public static Order of(int quantity, double unitPrice) {
        if (quantity < 0) throw new IllegalArgumentException("quantity");
        return new Order(quantity, unitPrice);
    }

    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    public void setCoupon(String coupon) { this.coupon = coupon; }

    public double total() {
        double t = quantity * unitPrice;
        if (coupon != null && coupon.startsWith("HALF")) t /= 2;
        if (quantity >= 100) t *= 0.9;
        return t;
    }

    public boolean isEmpty() { return quantity == 0; }
}
