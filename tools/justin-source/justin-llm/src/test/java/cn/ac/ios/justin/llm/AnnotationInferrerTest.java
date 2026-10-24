package cn.ac.ios.justin.llm;

import cn.ac.ios.justin.core.target.AnnotationInfo;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.BodyFacts;
import cn.ac.ios.justin.core.type.TypeRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationInferrerTest {

    private static final MethodTarget WITHDRAW = new MethodTarget("a.Svc", "withdraw", "(Ljava/lang/String;J)J", 0x0001, List.of(),
            List.of(new Parameter(0, "account", TypeRef.STRING, List.of()), new Parameter(1, "amount", new TypeRef.Primitive(TypeRef.Kind.LONG), List.of())),
            new TypeRef.Primitive(TypeRef.Kind.LONG), List.of(), List.of(), 10);

    private static AnnotationInferrer inferrer(String cannedAnswer) throws Exception {
        Path dir = Files.createTempDirectory("justin-llm-test");
        LlmConfig cfg = new LlmConfig(null, "test-model", "k", 10, 500, dir, List.of(), 10);
        LlmClient client = (system, user) -> cannedAnswer;
        return new AnnotationInferrer(client, new LlmCache(dir), cfg);
    }

    @Test
    void parsesConstraintsExamplesAndThrows() throws Exception {
        String answer = """
                ```json
                {"parameters":[
                  {"name":"account","constraints":[{"kind":"notBlank"},{"kind":"size","min":3,"max":16}],"examples":["alice","bob-1"],"invalid":["","  "]},
                  {"name":"amount","constraints":[{"kind":"range","min":1,"max":1000000},{"kind":"indexOf","target":"nope"}],"examples":[1,500],"invalid":[0,-1]},
                  {"name":"ghost","constraints":[{"kind":"notNull"}]}],
                 "throws":[{"exception":"IllegalArgumentException","when":"amount out of range"}],
                 "confidence":0.8}
                ```
                """;
        AnnotationInferrer inf = inferrer(answer);
        AnnotationInferrer.Inference r = inf.infer(owner(), WITHDRAW, BodyFacts.NONE, null);
        assertEquals(2, r.byParam().size());
        List<String> account = r.byParam().get(0).stream().map(AnnotationInfo::simpleName).toList();
        assertEquals(List.of("NotBlank", "Size", "Examples"), account);
        AnnotationInfo size = r.byParam().get(0).get(1);
        assertEquals(3, size.values().get("min"));
        AnnotationInfo ex = r.byParam().get(0).get(2);
        assertEquals(List.of("\"alice\"", "\"bob-1\""), ex.values().get("value"));
        assertEquals(List.of("\"\"", "\"  \""), ex.values().get("invalid"));
        List<String> amount = r.byParam().get(1).stream().map(AnnotationInfo::simpleName).toList();
        assertEquals(List.of("InRange", "Examples"), amount);   // indexOf with an unknown target is dropped
        assertEquals(List.of("1", "500"), r.byParam().get(1).get(1).values().get("value"));
        assertEquals(1, r.throwsHints().size());
        assertEquals(1, inf.calls());
        // second call hits the cache
        AnnotationInferrer.Inference again = inf.infer(owner(), WITHDRAW, BodyFacts.NONE, null);
        assertTrue(again.fromCache());
        assertEquals(1, inf.calls());
    }

    @Test
    void lowConfidenceKeepsOnlyExamples() throws Exception {
        AnnotationInferrer inf = inferrer("{\"parameters\":[{\"name\":\"amount\",\"constraints\":[{\"kind\":\"positive\"}],\"examples\":[7],\"invalid\":[-3]}],\"confidence\":0.2}");
        AnnotationInferrer.Inference r = inf.infer(owner(), WITHDRAW, BodyFacts.NONE, null);
        List<AnnotationInfo> a = r.byParam().get(1);
        assertEquals(1, a.size());
        assertEquals("Examples", a.get(0).simpleName());
        assertEquals(List.of(), a.get(0).values().get("invalid"));
    }

    @Test
    void garbageAnswerIsIgnored() throws Exception {
        AnnotationInferrer inf = inferrer("Sorry, I cannot help with that.");
        assertTrue(inf.infer(owner(), WITHDRAW, BodyFacts.NONE, null).byParam().isEmpty());
        assertEquals(1, inf.failures());
    }

    @Test
    void augmenterMergesWithoutOverridingDeclared() throws Exception {
        AnnotationInferrer inf = inferrer("{\"parameters\":[{\"name\":\"account\",\"constraints\":[{\"kind\":\"notNull\"},{\"kind\":\"email\"}]}],\"confidence\":0.9}");
        MethodTarget declared = new MethodTarget("a.Svc", "withdraw", "(Ljava/lang/String;J)J", 0x0001, List.of(),
                List.of(new Parameter(0, "account", TypeRef.STRING, List.of(new AnnotationInfo("jakarta.validation.constraints.NotNull", Map.of(), true))),
                        WITHDRAW.params().get(1)), WITHDRAW.returnType(), List.of(), List.of(), 10);
        MethodTarget out = new AnnotationAugmenter(inf, List.of()).augment(owner(), declared, BodyFacts.NONE);
        List<AnnotationInfo> anns = out.params().get(0).annotations();
        assertEquals(2, anns.size());
        assertEquals("jakarta.validation.constraints.NotNull", anns.get(0).binaryName()); // declared kept, model duplicate dropped
        assertEquals("Email", anns.get(1).simpleName());
    }

    private static ClassTarget owner() {
        return new ClassTarget("a.Svc", 0x0001, List.of(), TypeRef.OBJECT, List.of(), List.of(WITHDRAW), List.of(), List.of(), null, null, 61);
    }
}
