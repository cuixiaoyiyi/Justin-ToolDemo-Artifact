package cn.ac.ios.justin.samples;

/**
 * Branches that depend on the class of an object and on what other classes compute: instanceof and casts,
 * calls that are resolved by the class of the object, and conditions behind calls into another class.
 */
public class Parcel {

    /** What a parcel is sent by; the implementations differ in what they charge. */
    public interface Carrier {
        int charge(int grams);
    }

    public static class Bike implements Carrier {
        @Override public int charge(int grams) {
            if (grams > 5000) throw new IllegalArgumentException("too heavy for a bike");
            return 3;
        }
    }

    public static class Van implements Carrier {
        private final int base;
        public Van(int base) {
            if (base < 0) throw new IllegalArgumentException("base");
            this.base = base;
        }
        @Override public int charge(int grams) {
            if (grams > 20000) return base + 40;
            return base + 10;
        }
    }

    /** A class of its own, to be called from Parcel. */
    public static final class Tariff {
        private Tariff() {}

        public static int zone(String code) {
            if (code == null || code.length() != 2) return -1;
            if (code.equals("NL")) return 0;
            if (code.charAt(0) == 'D') return 1;
            return 2;
        }

        static int band(int grams) {
            if (grams <= 0) return 0;
            return grams < 1000 ? 1 : 2;
        }
    }

    private final int grams;

    public Parcel(int grams) {
        if (grams <= 0) throw new IllegalArgumentException("grams");
        this.grams = grams;
    }

    /** The class of the argument decides, then its contents. */
    public static String label(Object content) {
        if (content instanceof String) {
            String s = (String) content;
            if (s.startsWith("fragile")) return "handle with care";
            return "text";
        }
        if (content instanceof Integer) {
            int n = (Integer) content;
            if (n > 100) return "many";
            return "few";
        }
        if (content instanceof Carrier) return "carrier";
        return "other";
    }

    /** The conditions are in another class. */
    public String route(String code) {
        int zone = Tariff.zone(code);
        if (zone < 0) return "invalid";
        if (zone == 0) return "domestic";
        if (zone == 1 && Tariff.band(grams) == 2) return "neighbour, heavy";
        return "abroad";
    }

    /** What runs depends on the class of the carrier. */
    public String cost(Carrier by) {
        int c = by.charge(grams);
        if (c > 45) return "dear";
        if (c == 3) return "bike";
        return "van";
    }

    /** An object made here: its fields are what its constructor wrote. */
    public static int viaVan(int base, int grams) {
        Van v = new Van(base);
        int c = v.charge(grams);
        if (c == 77) return 1;
        return 0;
    }
}
