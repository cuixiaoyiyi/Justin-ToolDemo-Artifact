package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.scan.ClassPathScanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Not a test of behaviour: with -Ddiag.jar=subject.jar [-Ddiag.class=prefix] it prints why predicates
 * that involve string parameters cannot be used by the regex channel, as a histogram of reasons.
 */
class RegexDiagnosticsTest {

    @Test
    void histogramOfUnusablePredicates() throws Exception {
        String jar = System.getProperty("diag.jar");
        Assumptions.assumeTrue(jar != null, "set -Ddiag.jar");
        String prefix = System.getProperty("diag.class", "");
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(Path.of(jar)), n -> n.startsWith(prefix));
        SootUpLoader loader = SootUpLoader.minimal(List.of(Path.of(jar)), List.of());
        Map<String, Integer> reasons = new TreeMap<>();
        int preds = 0, inferable = 0, methods = 0;
        Set<String> segmentMethods = new java.util.TreeSet<>();
        for (ClassTarget c : classes) for (MethodTarget m : c.methods()) {
            Body body;
            try {
                body = loader.loadMethod(m).filter(x -> x.hasBody()).map(x -> (Body) x.getBody()).orElse(null);
            } catch (RuntimeException e) { continue; }
            if (body == null) continue;
            RegexChannel ch = new RegexChannel(body, 1);
            if (!ch.applicable()) continue;
            methods++;
            PathSlicer sl = new PathSlicer(body);
            Set<String> seen = new HashSet<>();
            for (Stmt t : sl.allBranchOutcomes()) for (List<Stmt> p : sl.pathsTo(t, 1)) {
                PathSlicer.StringPath sp = sl.slice(p, 0);
                if (sp == null) continue;
                for (Term.Pred pr : sp.preds()) {
                    if (!seen.add(pr.left() + pr.op() + pr.right())) continue;
                    boolean stringy = false;
                    for (int i : pr.params()) if (Term.isStringLike(ch.paramTypes().getOrDefault(i, ""))) stringy = true;
                    Set<String> why = new HashSet<>();
                    reasonsOf(pr.left(), why);
                    reasonsOf(pr.right(), why);
                    if (!stringy && why.isEmpty()) continue;
                    if (!stringy) { for (String w : why) reasons.merge("(no string param visible) " + w, 1, Integer::sum); continue; }
                    preds++;
                    Map<Integer, Object> env = new HashMap<>();
                    for (var e : ch.paramTypes().entrySet()) if (!Term.isStringLike(e.getValue())) env.put(e.getKey(), 1);
                    boolean ok = false;
                    for (int i : pr.params()) {
                        if (!Term.isStringLike(ch.paramTypes().getOrDefault(i, ""))) continue;
                        ApiRegexMapping.Inferred inf = ApiRegexMapping.infer(pr, i, env);
                        if (inf != null) ok = true;
                        if (inf instanceof ApiRegexMapping.Demands ds) {
                            for (ApiRegexMapping.Demand d : ds.list()) reasons.merge("(segment demand) " + d.part().mode() + " " + d.access(), 1, Integer::sum);
                            segmentMethods.add(m.id());
                        }
                    }
                    if (ok) inferable++;
                    else if (why.isEmpty()) reasons.merge("no mapping: " + shape(pr), 1, Integer::sum);
                    else for (String w : why) reasons.merge(w, 1, Integer::sum);
                }
            }
        }
        System.out.println("DIAG methods with string params " + methods + ", string predicates " + preds + ", inferable " + inferable);
        System.out.println("DIAG methods with segment demands " + segmentMethods.size() + ": " + segmentMethods.stream().limit(40).toList());
        reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(45).forEach(e -> System.out.println("DIAG " + e.getValue() + "  " + e.getKey()));
    }

    /** With -Ddiag.jar, -Ddiag.class=binary.Name and -Ddiag.method=name: the channel's paths, inferences and solutions for that method. */
    @Test
    void explainOneMethod() throws Exception {
        String jar = System.getProperty("diag.jar"), cls = System.getProperty("diag.class"), name = System.getProperty("diag.method");
        Assumptions.assumeTrue(jar != null && cls != null && name != null, "set -Ddiag.jar -Ddiag.class -Ddiag.method");
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(Path.of(jar)), n -> n.equals(cls));
        SootUpLoader loader = SootUpLoader.minimal(List.of(Path.of(jar)), List.of());
        java.util.function.Function<sootup.core.signatures.MethodSignature, java.util.Optional<Body>> resolver =
                sig -> loader.loadMethod(sig).filter(x -> x.hasBody()).map(x -> (Body) x.getBody());
        for (MethodTarget m : classes.get(0).methods()) {
            if (!m.name().equals(name)) continue;
            Body body = loader.loadMethod(m).orElseThrow().getBody();
            System.out.println("EXPLAIN ==== " + m.id());
            for (Stmt st : body.getStmts()) System.out.println("EXPLAIN   stmt " + st.getPositionInfo().getStmtPosition().getFirstLine() + ": " + st);
            RegexChannel ch = new RegexChannel(body, 42, resolver);
            PathSlicer sl = new PathSlicer(body, resolver);
            Set<String> seen = new HashSet<>();
            for (Stmt t : sl.allBranchOutcomes()) for (List<Stmt> p : sl.pathsTo(t, 3)) {
                PathSlicer.StringPath sp0 = sl.slice(p, 0);
                if (sp0 == null) continue;
                for (PathSlicer.StringPath sp : sl.expand(sp0)) {
                    if (!seen.add(sp.preds().toString())) continue;
                    System.out.println("EXPLAIN   path to line " + PathSlicer.lineOf(t));
                    for (Term.Pred pr : sp.preds()) {
                        StringBuilder inf = new StringBuilder();
                        Map<Integer, Object> env = new HashMap<>();
                        for (var e : ch.paramTypes().entrySet()) if (!Term.isStringLike(e.getValue())) env.put(e.getKey(), 1);
                        for (int i : pr.params()) inf.append(" p").append(i).append("=").append(ApiRegexMapping.infer(pr, i, env));
                        System.out.println("EXPLAIN     " + (pr.holds() ? "" : "NOT ") + brief(pr.left()) + " " + pr.op() + " " + brief(pr.right()) + "  =>" + inf);
                    }
                }
            }
            for (RegexChannel.Solution so : ch.generate(Set.of(), true).solutions()) System.out.println("EXPLAIN   solution " + so);
        }
    }

    private static String brief(Term t) {
        if (t instanceof Term.Const c) return c.value() instanceof String s ? '"' + s + '"' : String.valueOf(c.value());
        if (t instanceof Term.Param p) return "p" + p.index();
        if (t instanceof Term.Unknown u) return "?" + u.why();
        if (t instanceof Term.Call c) {
            StringBuilder sb = new StringBuilder(c.base() == null ? c.owner().substring(c.owner().lastIndexOf('.') + 1) : brief(c.base())).append('.').append(c.name()).append('(');
            for (int i = 0; i < c.args().size(); i++) sb.append(i > 0 ? ", " : "").append(brief(c.args().get(i)));
            return sb.append(')').toString();
        }
        if (t instanceof Term.Concat c) { StringBuilder sb = new StringBuilder("concat["); for (Term a : c.parts()) sb.append(brief(a)).append(' '); return sb.append(']').toString(); }
        if (t instanceof Term.Elem e) return brief(e.array()) + "[" + brief(e.index()) + "]";
        if (t instanceof Term.Len l) return brief(l.array()) + ".length";
        if (t instanceof Term.Bin b) return "(" + brief(b.left()) + " " + b.op() + " " + brief(b.right()) + ")";
        return t.toString();
    }

    private static String shape(Term.Pred p) {
        Term t = p.left() instanceof Term.Const ? p.right() : p.left();
        return t instanceof Term.Call c ? c.owner() + "." + c.name() : t.getClass().getSimpleName();
    }

    private static void reasonsOf(Term t, Set<String> out) {
        if (t instanceof Term.Unknown u) out.add("unknown: " + u.why().replaceAll("of [^ ]+ on", "of X on"));
        else if (t instanceof Term.Call c) {
            if (!Term.EVALUABLE.contains(c.owner())) out.add("call: " + c.owner() + "." + c.name());
            if (c.base() != null) reasonsOf(c.base(), out);
            for (Term a : c.args()) reasonsOf(a, out);
        } else if (t instanceof Term.Concat c) for (Term a : c.parts()) reasonsOf(a, out);
        else if (t instanceof Term.Elem e) { reasonsOf(e.array(), out); reasonsOf(e.index(), out); }
        else if (t instanceof Term.Len l) reasonsOf(l.array(), out);
        else if (t instanceof Term.Bin b) { reasonsOf(b.left(), out); reasonsOf(b.right(), out); }
    }
}
