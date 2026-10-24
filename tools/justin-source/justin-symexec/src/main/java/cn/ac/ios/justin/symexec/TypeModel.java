package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.symexec.SymValue.Kind;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The class of an object as part of the path condition. For an object whose class is known (made by new, a
 * string, a boxed number) instanceof is decided on the spot. For one that comes from outside, the answer is a
 * boolean of the path: the solver chooses, consistently over all the questions asked about the same object,
 * and the input is then built as an instance of a class that gives these answers.
 *
 * <p>A cast to String or to a boxed type gives the object contents: a string or number of its own, which the
 * conditions after the cast constrain and the input takes from the model.
 */
final class TypeModel {
    private final SymbolicExecutor ex;
    private final Context ctx;
    private final SymConfig cfg;
    private TypeOracle oracle = TypeOracle.JDK;

    TypeModel(SymbolicExecutor ex, SymConfig cfg) {
        this.ex = ex;
        this.ctx = ex.ctx();
        this.cfg = cfg;
    }

    void setOracle(TypeOracle o) { this.oracle = o == null ? TypeOracle.JDK : o; }

    TypeOracle oracle() { return oracle; }

    /** The class a value is known to be an instance of: exactly (strings, boxed numbers, new) or as an upper bound. */
    private static String known(SymValue v) {
        if (v.kind == Kind.STR) return "java.lang.String";
        return v.refType;
    }

    private static boolean exact(SymValue v) {
        return v.kind == Kind.STR || v.isNumeric() && v.refType != null || v.exactType;
    }

    private BoolExpr notNull(SymValue v) {
        if (v.isDefinitelyNull()) return ctx.mkFalse();
        return v.nullFlag == null ? ctx.mkTrue() : ctx.mkNot(v.nullFlag);
    }

    /** v instanceof type, as a condition of the path. */
    BoolExpr instanceOf(SymState s, SymValue v, String type) {
        if (v == null || v.kind == Kind.UNKNOWN && v.id == 0) return ctx.mkBoolConst("is_u" + ex.nextFresh());
        if (v.isDefinitelyNull()) return ctx.mkFalse();
        String known = known(v);
        if (known != null) {
            Boolean up = oracle.subtype(known, type);
            if (Boolean.TRUE.equals(up)) return notNull(v);
            if (exact(v) && up != null) return ctx.mkFalse();
            // two classes, neither below the other: no object is an instance of both
            Boolean down = oracle.subtype(type, known);
            if (Boolean.FALSE.equals(up) && Boolean.FALSE.equals(down) && !oracle.isInterface(type) && !oracle.isInterface(known)) return ctx.mkFalse();
        }
        if (v.id == 0) return ctx.mkBoolConst("is_u" + ex.nextFresh());
        String key = v.id + ":" + type;
        BoolExpr b = s.instanceOf.get(key);
        if (b != null) return b;
        b = ctx.mkBoolConst("is" + v.id + "_" + type);
        s.pc.add(ctx.mkImplies(b, notNull(v)));
        String prefix = v.id + ":";
        for (Map.Entry<String, BoolExpr> e : s.instanceOf.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String other = e.getKey().substring(prefix.length());
            Boolean below = oracle.subtype(type, other), above = oracle.subtype(other, type);
            if (Boolean.TRUE.equals(below)) s.pc.add(ctx.mkImplies(b, e.getValue()));
            if (Boolean.TRUE.equals(above)) s.pc.add(ctx.mkImplies(e.getValue(), b));
            if (Boolean.FALSE.equals(below) && Boolean.FALSE.equals(above) && !oracle.isInterface(type) && !oracle.isInterface(other)) {
                s.pc.add(ctx.mkNot(ctx.mkAnd(b, e.getValue())));
            }
        }
        s.instanceOf.put(key, b);
        return b;
    }

    /**
     * (type) v for a reference type: the path goes on when v is null or an instance (the other case is a
     * ClassCastException, not followed). A cast to String or a boxed type returns the contents.
     */
    SymValue cast(SymState s, SymValue v, String type) {
        if (v == null || v.kind != Kind.REF || v.isCollection() || v.isArray() || v.id == 0 || type.endsWith("[]")) return v;
        if (v.isDefinitelyNull()) return v;
        BoolExpr is = instanceOf(s, v, type);
        BoolExpr isNull = v.nullFlag == null ? ctx.mkFalse() : v.nullFlag;
        s.pc.add(ctx.mkOr(isNull, is));
        Kind k = CollectionSummaries.kindOfElement(type);
        if (k == null || v.exactType) return v;
        String key = v.id + ":" + k;
        SymValue view = s.views.get(key);
        if (view == null) {
            String name = "o" + v.id + "_" + k;
            switch (k) {
                case STR: {
                    var str = ctx.mkConst(name, ctx.getStringSort());
                    s.pc.add(ctx.mkLe(ctx.mkLength(str), ctx.mkInt(cfg.maxStringLength())));
                    view = SymValue.str(str, null);
                    break;
                }
                case BV64: view = SymValue.bv64(ctx.mkBVConst(name, 64)); break;
                case REAL: view = SymValue.real(ctx.mkConst(name, ctx.getRealSort())); break;
                default: {
                    BitVecExpr b = ctx.mkBVConst(name, 32);
                    long lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE;
                    switch (type) {
                        case "java.lang.Boolean": lo = 0; hi = 1; break;
                        case "java.lang.Character": lo = 0; hi = 0xFFFF; break;
                        case "java.lang.Byte": lo = -128; hi = 127; break;
                        case "java.lang.Short": lo = -32768; hi = 32767; break;
                        default: break;
                    }
                    if (lo != Integer.MIN_VALUE) { s.pc.add(ctx.mkBVSGE(b, ex.bv32(lo))); s.pc.add(ctx.mkBVSLE(b, ex.bv32(hi))); }
                    view = SymValue.bv32(b);
                }
            }
            s.views.put(key, view);
            s.viewTypes.put(key, type);
        }
        return v.nullFlag == null ? view : view.orNull(v.nullFlag);
    }

    /** The path assumes that the object is of exactly this class (a virtual call was resolved for it). */
    void assume(SymState s, SymValue v, String exactClass) {
        if (v == null || v.id == 0 || v.exactType) return;
        s.pc.add(instanceOf(s, v, exactClass));
        s.exactly.put(v.id, exactClass);
    }

    /** What the model says about the class of an object: the types it is an instance of, and those it is not. */
    private void answers(Model model, SymState s, SymValue v, List<String> is, List<String> isNot) {
        String prefix = v.id + ":";
        for (Map.Entry<String, BoolExpr> e : s.instanceOf.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String type = e.getKey().substring(prefix.length());
            if (model.eval(e.getValue(), true).isTrue()) is.add(type); else isNot.add(type);
        }
    }

    /** True when the path asked about the class of the object or gave it contents. */
    boolean concerns(SymState s, SymValue v) {
        if (v == null || v.id == 0) return false;
        String prefix = v.id + ":";
        for (String k : s.instanceOf.keySet()) if (k.startsWith(prefix)) return true;
        return false;
    }

    /**
     * The argument for an object parameter whose class the path depends on: a string or number with the contents
     * of the model, or an instance of a class that is what the model says and is not what it denies. Null when
     * there is nothing to say or no such value can be built.
     */
    Value value(Model model, SymState s, SymValue v, TypeRef declared, ValueSource values) {
        List<String> is = new ArrayList<>(), isNot = new ArrayList<>();
        answers(model, s, v, is, isNot);
        if (is.isEmpty() && isNot.isEmpty()) return null;
        for (String type : is) {
            Kind k = CollectionSummaries.kindOfElement(type);
            if (k == null) continue;
            SymValue view = s.views.get(v.id + ":" + k);
            String viewType = s.viewTypes.get(v.id + ":" + k);
            if (view != null && viewType != null && Boolean.TRUE.equals(oracle.subtype(viewType, type))) {
                Value c = ex.contentValue(model, view, viewType);
                if (c != null) return c;
            }
        }
        String exact = s.exactly.get(v.id);
        List<String> candidates = new ArrayList<>();
        if (exact != null) candidates.add(exact);
        // the most specific of the types asked for, then what implements it
        String lowest = null;
        for (String type : is) {
            if (lowest == null || Boolean.TRUE.equals(oracle.subtype(type, lowest))) lowest = type;
        }
        if (lowest != null) {
            if (oracle.instantiable(lowest)) candidates.add(lowest);
            candidates.addAll(oracle.implementations(lowest));
        } else {
            String top = declared.erasureName();
            if (oracle.instantiable(top)) candidates.add(top);
            candidates.addAll(oracle.implementations(top));
        }
        int tried = 0;
        for (String c : candidates) {
            if (!fits(c, is, isNot)) continue;
            if (++tried > 4) break;
            Value made = values.generate(TypeRef.ClassRef.of(c));
            if (made == null || made instanceof Value.NullValue) continue;
            String of = classOf(made);
            if (of != null && !fits(of, is, isNot)) continue;
            return made;
        }
        return null;
    }

    private boolean fits(String cls, List<String> is, List<String> isNot) {
        for (String t : is) if (!Boolean.TRUE.equals(oracle.subtype(cls, t))) return false;
        for (String t : isNot) if (!Boolean.FALSE.equals(oracle.subtype(cls, t))) return false;
        return true;
    }

    /** The class of what a value builds, where the value says it. */
    private static String classOf(Value v) {
        if (v instanceof Value.StringValue) return "java.lang.String";
        if (v instanceof Value.PrimitiveValue p) return p.kind().boxed;
        if (v instanceof Value.EnumValue e) return e.enumClass();
        if (v instanceof Value.ObjectValue o) {
            if (o.recipe() instanceof Value.Recipe.ConstructorCall c) return c.className();
            if (o.recipe() instanceof Value.Recipe.Boxed b) return b.primitive().kind().boxed;
            if (o.recipe() instanceof Value.Recipe.Sequence q && q.base() instanceof Value.Recipe.ConstructorCall c) return c.className();
        }
        return null;
    }
}
