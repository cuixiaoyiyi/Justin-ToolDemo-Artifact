package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.scan.ClassPathScanner;
import cn.ac.ios.justin.strategies.TypeInfoProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sootup.core.model.Body;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** The class of an object in the path condition, and calls into other classes (sample class Parcel). */
class TypeModelTest {
    private static final String P = "cn.ac.ios.justin.samples.Parcel";

    private static SootUpLoader loader;
    private static ClassTarget parcel;
    private static TypeOracle oracle;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName(P).getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        parcel = classes.stream().filter(c -> c.binaryName().equals(P)).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
        TypeInfoProvider types = new TypeInfoProvider() {
            @Override public Optional<ClassTarget> lookup(String n) { return classes.stream().filter(c -> c.binaryName().equals(n)).findFirst(); }
            @Override public List<String> enumConstants(String n) { return List.of(); }
            @Override public List<String> knownImplementations(String n) { return n.equals(P + "$Carrier") ? List.of(P + "$Bike", P + "$Van") : List.of(); }
        };
        oracle = new Hierarchy(loader, types, new ConcurrentHashMap<>());
    }

    private static MethodTarget method(String name) {
        return parcel.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static SymResult run(String name, boolean inline, boolean types) {
        MethodTarget m = method(name);
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        SymConfig cfg = SymConfig.defaults().withObjects(new SymConfig.ObjectModel(8, 0, false, inline, types));
        try (SymbolicExecutor ex = new SymbolicExecutor(cfg)) {
            ex.setCalleeResolver(sig -> {
                try {
                    return loader.view().getMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody());
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            });
            ex.setTypeOracle(oracle);
            ex.setValueSource(new ValueSource() {
                @Override public Value generate(TypeRef type) {
                    String n = type.erasureName();
                    if (n.equals(P + "$Bike")) return new Value.ObjectValue(TypeRef.ClassRef.of(n), new Value.Recipe.ConstructorCall(n, "()V", List.of()));
                    if (n.equals(P + "$Van")) return new Value.ObjectValue(TypeRef.ClassRef.of(n), new Value.Recipe.ConstructorCall(n, "(I)V", List.of(Value.PrimitiveValue.of(50))));
                    return null;
                }
                @Override public TypeRef concrete(TypeRef declared) { return declared; }
            });
            ex.start(m, body, Set.of());
            return new SymResult(new ArrayList<>(ex.resume(20_000)), ex.stats());
        }
    }

    @Test
    void theHierarchyKnowsProjectAndJdkClasses() {
        assertEquals(Boolean.TRUE, oracle.subtype(P + "$Van", P + "$Carrier"));
        assertEquals(Boolean.FALSE, oracle.subtype(P + "$Van", P + "$Bike"));
        assertEquals(Boolean.TRUE, oracle.subtype("java.lang.Integer", "java.lang.Number"));
        assertEquals(Boolean.FALSE, oracle.subtype("java.lang.String", "java.lang.Number"));
        assertEquals(Boolean.TRUE, oracle.subtype(P + "$Van", "java.lang.Object"));
        assertNull(oracle.subtype("no.such.Type", "java.lang.Number"));
        assertTrue(oracle.isInterface(P + "$Carrier"));
        assertTrue(oracle.instantiable(P + "$Bike"));
        assertFalse(oracle.instantiable(P + "$Carrier"));
    }

    @Test
    void theClassOfAnArgumentAndItsContents() {
        SymResult r = run("label", false, true);
        boolean care = false, text = false, many = false, few = false, carrier = false;
        for (SymResult.PathInput p : r.inputs()) {
            Value v = p.args().get(0);
            if (v instanceof Value.StringValue s) { if (s.value().startsWith("fragile")) care = true; else text = true; }
            else if (v instanceof Value.PrimitiveValue n) { if ((Integer) n.boxed() > 100) many = true; else few = true; }
            else if (v instanceof Value.ObjectValue o && o.type().erasureName().startsWith(P + "$")) carrier = true;
        }
        assertTrue(care && text && many && few && carrier, "care=" + care + " text=" + text + " many=" + many + " few=" + few + " carrier=" + carrier + " " + r.inputs());
    }

    @Test
    void withoutTheModelInstanceofIsAnUnknown() {
        SymResult r = run("label", false, false);
        assertTrue(r.inputs().stream().allMatch(p -> p.args().get(0) == null || p.args().get(0) instanceof Value.NullValue), r.inputs().toString());
    }

    @Test
    void conditionsInAnotherClass() {
        SymResult off = run("route", false, false);
        SymResult on = run("route", true, false);
        Set<String> codes = new HashSet<>();
        for (SymResult.PathInput p : on.inputs()) if (p.args().get(0) instanceof Value.StringValue s) codes.add(s.value());
        assertTrue(codes.contains("NL"), codes.toString());
        assertTrue(codes.stream().anyMatch(c -> c.length() == 2 && c.charAt(0) == 'D'), codes.toString());
        assertTrue(codes.stream().anyMatch(c -> c.length() != 2), codes.toString());
        assertTrue(on.stats().inlinedCalls() > 0);
        assertEquals(0, off.stats().inlinedCalls());
        Set<String> without = new HashSet<>();
        for (SymResult.PathInput p : off.inputs()) if (p.args().get(0) instanceof Value.StringValue s) without.add(s.value());
        assertFalse(without.contains("NL"), "the comparison with NL is in Tariff: " + without);
    }

    @Test
    void aVirtualCallRunsWhatTheClassOfTheObjectSays() {
        SymResult r = run("cost", true, true);
        Set<String> classes = new HashSet<>();
        for (SymResult.PathInput p : r.inputs()) if (p.args().get(0) instanceof Value.ObjectValue o) classes.add(o.type().erasureName());
        assertTrue(classes.contains(P + "$Bike") && classes.contains(P + "$Van"), "one path per class of carrier: " + classes + " " + r.inputs());
    }

    @Test
    void anObjectMadeOnThePathHasTheFieldsItsConstructorWrote() {
        SymResult r = run("viaVan", true, true);
        boolean hit = false;
        for (SymResult.PathInput p : r.inputs()) {
            if (p.thrown() != null) continue;
            int base = (Integer) ((Value.PrimitiveValue) p.args().get(0)).boxed(), grams = (Integer) ((Value.PrimitiveValue) p.args().get(1)).boxed();
            int c = grams > 20000 ? base + 40 : base + 10;
            if (base >= 0 && c == 77) hit = true;
        }
        assertTrue(hit, "base + 40 == 77 or base + 10 == 77: " + r.inputs());
    }
}
