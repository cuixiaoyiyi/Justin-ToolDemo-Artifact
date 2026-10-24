package cn.ac.ios.justin.llm;

import cn.ac.ios.justin.core.target.AnnotationInfo;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.BodyFacts;
import cn.ac.ios.justin.core.test.Relation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks the model for "virtual annotations" on a method's parameters and turns the (validated) JSON
 * answer into AnnotationInfo objects the annotation strategy already understands. Unknown kinds are
 * dropped; low-confidence answers keep only example values (no contract, so no "illegal" tagging).
 */
public final class AnnotationInferrer {

    /** Prompt-format version: bump when the prompt changes so cached answers are not reused. */
    static final String PROMPT_VERSION = "v1";
    private static final double CONTRACT_CONFIDENCE = 0.5;
    private static final String LLM_PKG = "justin.llm.";

    private static final String SYSTEM = """
            You infer input contracts for Java methods so a test generator can pick boundary, inside and invalid values,
            and output contracts so it can assert the result. Answer with one JSON object and nothing else (no prose, no code fences). Schema:
            {"parameters":[{"name":"<param>","constraints":[<constraint>,...],"examples":["<literal>",...],"invalid":["<literal>",...]}],
             "returns":{"constraints":[<constraint>,...]},
             "throws":[{"exception":"<simple or binary name>","when":"<short condition>"}],
             "confidence":<0..1>}
            <constraint> is one of:
             {"kind":"notNull"} | {"kind":"nullable"} | {"kind":"range","min":<int>,"max":<int>} | {"kind":"size","min":<int>,"max":<int>} | {"kind":"positive"} | {"kind":"nonNegative"}
             | {"kind":"notEmpty"} | {"kind":"notBlank"} | {"kind":"email"} | {"kind":"pattern","regex":"<java regex>"} | {"kind":"oneOf","values":[...]}
             | {"kind":"indexOf","target":"<other param name>"} | {"kind":"arrayPara","target":"<array/string/list param name>","flag":0|-1|1}
             | {"kind":"past"} | {"kind":"future"} | {"kind":"subClass","class":"<binary class name>"}
             | {"kind":"field","name":"<field of the object>","constraints":[<constraint>,...]} | {"kind":"element","index":<int>,"constraints":[<constraint>,...]}
            Rules: include only parameters you have evidence for; literals are Java-like ("text" for strings, 'c' for chars, 42, -1, 3.5, true);
            examples are realistic values that exercise interesting paths; invalid values should make the method reject the input;
            "range" only for integral parameters or return values, "size" for strings/arrays/collections; "arrayPara" flag 0 = equals the length, -1 = at most, 1 = at least;
            "field" names only fields that exist in the parameter's class and may appear inside "element" (an element's field) but nests no deeper;
            "returns" describes the return value only when the code guarantees it; never invent parameters; confidence reflects how sure the evidence is.
            """;

    private final LlmClient client;
    private final LlmCache cache;
    private final LlmConfig cfg;
    private final ObjectMapper om = new ObjectMapper();
    private int calls, cacheHits, failures;

    public AnnotationInferrer(LlmClient client, LlmCache cache, LlmConfig cfg) {
        this.client = client;
        this.cache = cache;
        this.cfg = cfg;
    }

    private final Map<String, Integer> failureReasons = new java.util.TreeMap<>();

    public synchronized int calls() { return calls; }
    public synchronized int cacheHits() { return cacheHits; }
    public synchronized int failures() { return failures; }
    public synchronized Map<String, Integer> failureReasons() { return new java.util.TreeMap<>(failureReasons); }

    private synchronized void fail(String reason) {
        failures++;
        failureReasons.merge(reason, 1, Integer::sum);
    }

    /** Result: per parameter index, the inferred annotations; plus the model's throws hints. */
    public record Inference(Map<Integer, List<AnnotationInfo>> byParam, List<AnnotationInfo> returns, List<String> throwsHints, double confidence, boolean fromCache) {
        public Inference(Map<Integer, List<AnnotationInfo>> byParam, List<String> throwsHints, double confidence, boolean fromCache) {
            this(byParam, List.of(), throwsHints, confidence, fromCache);
        }
        public static final Inference EMPTY = new Inference(Map.of(), List.of(), 0, false);
    }

    public Inference infer(ClassTarget owner, MethodTarget m, BodyFacts facts, String sourceSnippet) {
        if (m.params().isEmpty()) return Inference.EMPTY;
        for (String p : cfg.excludePackages()) if (owner.binaryName().startsWith(p)) return Inference.EMPTY;
        String prompt = prompt(owner, m, facts, sourceSnippet);
        String key = cache.key(cfg.model() + "|" + PROMPT_VERSION, prompt);
        String answer;
        boolean cached = false;
        try {
            var hit = cache.get(key);
            if (hit.isPresent() && !hit.get().isBlank()) { answer = hit.get(); cached = true; synchronized (this) { cacheHits++; } }
            else {
                synchronized (this) {
                    if (calls >= cfg.maxCallsPerRun()) return Inference.EMPTY;
                    calls++;
                }
                answer = client.complete(SYSTEM, prompt);
                // an empty answer (e.g. the model spent its whole budget thinking) is a failure, not a cacheable result
                if (answer == null || answer.isBlank()) { fail("empty answer"); return Inference.EMPTY; }
                cache.put(key, answer);
            }
        } catch (Exception e) {
            String why = e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName();
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("timeout") || msg.contains("Timeout") || why.contains("Timeout")) why = "timeout";
            else if (msg.contains("429") || msg.contains("rate")) why = "rate-limited";
            else if (msg.contains("5") && msg.contains("status")) why = "server error";
            fail(why);
            return Inference.EMPTY;
        }
        return parse(answer, m, cached);
    }

    // ---------- prompt ----------

    String prompt(ClassTarget owner, MethodTarget m, BodyFacts facts, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("Class: ").append(owner.binaryName().replace('$', '.')).append('\n');
        sb.append("Method: ").append(m.isStatic() ? "static " : "").append(m.returnType().toSource()).append(' ')
                .append(m.isConstructor() ? m.simpleClassName() : m.name()).append('(')
                .append(m.params().stream().map(p -> p.type().toSource() + " " + p.name() + existing(p)).collect(Collectors.joining(", "))).append(")\n");
        if (!m.thrownExceptions().isEmpty()) sb.append("Declared throws: ").append(String.join(", ", m.thrownExceptions())).append('\n');
        if (facts != null && facts.available()) {
            if (!facts.explicitThrows().isEmpty()) sb.append("Explicitly thrown in body: ").append(String.join(", ", facts.explicitThrows())).append('\n');
            if (!facts.intConstants().isEmpty()) sb.append("Int constants in body: ").append(facts.intConstants().stream().limit(20).map(String::valueOf).collect(Collectors.joining(", "))).append('\n');
            if (!facts.stringConstants().isEmpty()) sb.append("String constants in body: ").append(facts.stringConstants().stream().limit(12).map(s -> '"' + s + '"').collect(Collectors.joining(", "))).append('\n');
            for (Relation r : facts.relations()) sb.append("Relation: ").append(m.params().get(r.dependent()).name()).append(' ').append(r.kind()).append(' ').append(m.params().get(r.base()).name()).append('\n');
        }
        List<String> siblings = owner.methods().stream().filter(x -> x.isPublic() && !x.isConstructor() && !x.equals(m)).map(MethodTarget::name).distinct().limit(15).toList();
        if (!siblings.isEmpty()) sb.append("Other public methods: ").append(String.join(", ", siblings)).append('\n');
        if (source != null && !source.isBlank()) sb.append("Source:\n").append(source.length() > 6000 ? source.substring(0, 6000) + "\n..." : source).append('\n');
        return sb.toString();
    }

    private static String existing(Parameter p) {
        if (p.annotations().isEmpty()) return "";
        return " /*@" + p.annotations().stream().map(AnnotationInfo::simpleName).collect(Collectors.joining(" @")) + "*/";
    }

    // ---------- parsing ----------

    Inference parse(String answer, MethodTarget m, boolean cached) {
        JsonNode root;
        try {
            root = om.readTree(stripFences(answer));
        } catch (Exception e) {
            fail("unparsable JSON");
            return Inference.EMPTY;
        }
        if (root == null || !root.isObject()) return Inference.EMPTY;
        double confidence = root.path("confidence").isNumber() ? root.path("confidence").asDouble() : 0.5;
        boolean contracts = confidence >= CONTRACT_CONFIDENCE;
        Map<String, Integer> byName = new HashMap<>();
        for (Parameter p : m.params()) byName.put(p.name(), p.index());
        Map<Integer, List<AnnotationInfo>> out = new HashMap<>();
        for (JsonNode p : root.path("parameters")) {
            Integer idx = byName.get(p.path("name").asText());
            if (idx == null) continue;
            List<AnnotationInfo> anns = new ArrayList<>();
            if (contracts) anns.addAll(constraints(p.path("constraints"), byName, 0, null));
            List<Object> examples = new ArrayList<>(), invalid = new ArrayList<>();
            for (JsonNode e : p.path("examples")) examples.add(literal(e));
            if (contracts) for (JsonNode e : p.path("invalid")) invalid.add(literal(e));
            if (!examples.isEmpty() || !invalid.isEmpty()) {
                Map<String, Object> v = new HashMap<>();
                v.put("value", examples);
                v.put("invalid", invalid);
                anns.add(new AnnotationInfo(LLM_PKG + "Examples", v, false));
            }
            if (!anns.isEmpty()) out.put(idx, anns);
        }
        List<String> throwsHints = new ArrayList<>();
        for (JsonNode t : root.path("throws")) throwsHints.add(t.path("exception").asText() + " when " + t.path("when").asText());
        List<AnnotationInfo> returns = contracts ? constraints(root.path("returns").path("constraints"), byName, 0, null) : List.of();
        return new Inference(out, returns, throwsHints, confidence, cached);
    }

    /**
     * Plain constraints first, structural groups (field / element) last, so the annotation strategy's
     * left-to-right reading attaches nested constraints to the selected field or element. A field inside
     * an element records the element index ("in"), the only nesting allowed.
     */
    private static List<AnnotationInfo> constraints(JsonNode arr, Map<String, Integer> params, int depth, Integer inElement) {
        List<AnnotationInfo> plain = new ArrayList<>(), structural = new ArrayList<>();
        if (arr == null || !arr.isArray()) return plain;
        for (JsonNode c : arr) {
            String kind = c.path("kind").asText();
            if (kind.equals("field") || kind.equals("element")) {
                if (depth >= 1 && (kind.equals("element") || inElement == null)) continue;
                Map<String, Object> v = new HashMap<>();
                Integer elementIndex = null;
                if (kind.equals("field")) {
                    String name = c.path("name").asText();
                    if (name.isEmpty()) continue;
                    v.put("value", name);
                    if (inElement != null) v.put("in", inElement);
                } else {
                    if (!c.path("index").isInt() || c.path("index").asInt() < 0 || c.path("index").asInt() > 16) continue;
                    elementIndex = c.path("index").asInt();
                    v.put("value", elementIndex);
                }
                List<AnnotationInfo> nested = constraints(c.path("constraints"), params, depth + 1, elementIndex);
                if (nested.isEmpty()) continue;
                structural.add(new AnnotationInfo(LLM_PKG + (kind.equals("field") ? "Field" : "Element"), v, false));
                structural.addAll(nested);
                continue;
            }
            constraint(c, params).ifPresent(plain::add);
        }
        plain.addAll(structural);
        return plain;
    }

    private static java.util.Optional<AnnotationInfo> constraint(JsonNode c, Map<String, Integer> params) {
        String kind = c.path("kind").asText();
        Map<String, Object> v = new HashMap<>();
        switch (kind) {
            case "notNull": return ann("NotNull", v);
            case "nullable": return ann("Nullable", v);
            case "positive": return ann("Positive", v);
            case "nonNegative": return ann("PositiveOrZero", v);
            case "notEmpty": return ann("NotEmpty", v);
            case "notBlank": return ann("NotBlank", v);
            case "email": return ann("Email", v);
            case "past": return ann("Past", v);
            case "future": return ann("Future", v);
            case "subClass": {
                String cls = c.path("class").asText();
                if (cls.isEmpty() || !cls.matches("[A-Za-z_$][\\w$.]*")) return java.util.Optional.empty();
                v.put("value", cls);
                return ann("SubClass", v);
            }
            case "arrayPara": {
                Integer idx = params.get(c.path("target").asText());
                if (idx == null) return java.util.Optional.empty();
                v.put("paraIndex", idx);
                v.put("flag", c.path("flag").isInt() ? Math.max(-1, Math.min(1, c.path("flag").asInt())) : 0);
                return ann("ArrayPara", v);
            }
            case "range": {
                long min = c.hasNonNull("min") ? c.path("min").asLong() : Long.MIN_VALUE;
                long max = c.hasNonNull("max") ? c.path("max").asLong() : Long.MAX_VALUE;
                // a range spanning the whole int/long domain says nothing
                if (min <= Integer.MIN_VALUE && max >= Integer.MAX_VALUE) return java.util.Optional.empty();
                if (min > max) return java.util.Optional.empty();
                if (c.hasNonNull("min")) v.put("min", min);
                if (c.hasNonNull("max")) v.put("max", max);
                return v.isEmpty() ? java.util.Optional.empty() : ann("InRange", v);
            }
            case "size": {
                if (c.hasNonNull("min")) v.put("min", Math.max(0, c.path("min").asInt()));
                if (c.hasNonNull("max")) v.put("max", Math.max(0, c.path("max").asInt()));
                if (v.containsKey("min") && v.containsKey("max") && (Integer) v.get("min") > (Integer) v.get("max")) return java.util.Optional.empty();
                return v.isEmpty() ? java.util.Optional.empty() : ann("Size", v);
            }
            case "pattern": {
                if (!c.hasNonNull("regex")) return java.util.Optional.empty();
                String re = c.path("regex").asText();
                try { java.util.regex.Pattern.compile(re); } catch (java.util.regex.PatternSyntaxException e) { return java.util.Optional.empty(); }
                v.put("regexp", re);
                return ann("Pattern", v);
            }
            case "indexOf":
                String t = c.path("target").asText();
                if (!params.containsKey(t)) return java.util.Optional.empty();
                v.put("value", t);
                return ann("IndexOf", v);
            case "oneOf": {
                List<Object> vals = new ArrayList<>();
                for (JsonNode x : c.path("values")) vals.add(literal(x));
                if (vals.isEmpty()) return java.util.Optional.empty();
                v.put("value", vals);
                v.put("invalid", List.of());
                return ann("Examples", v);
            }
            default: return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<AnnotationInfo> ann(String simple, Map<String, Object> values) {
        return java.util.Optional.of(new AnnotationInfo(LLM_PKG + simple, values, false));
    }

    /** JSON scalars become literals the special-value parser understands: strings quoted, numbers/booleans as text. */
    private static String literal(JsonNode n) {
        if (n.isTextual()) {
            String s = n.asText();
            // already a Java literal? ("x", 'c', 42, true) - keep; otherwise quote as a string
            if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) return s;
            if (s.matches("-?\\d+(\\.\\d+)?[LlFfDd]?") || s.equals("true") || s.equals("false")) return s;
            return '"' + s + '"';
        }
        return n.asText();
    }

    static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        int brace = t.indexOf('{');
        int last = t.lastIndexOf('}');
        return brace >= 0 && last > brace ? t.substring(brace, last + 1) : t;
    }
}
