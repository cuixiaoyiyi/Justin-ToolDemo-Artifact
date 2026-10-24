package cn.ac.ios.justin.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the JSON-RPC daemon through in-memory streams: ping, an unknown method, a small generate job, status and result. */
class DaemonServerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static List<JsonNode> serve(String... requests) throws Exception {
        String input = String.join("\n", requests) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DaemonServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out).run();
        List<JsonNode> lines = new ArrayList<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank()) lines.add(OM.readTree(l));
        return lines;
    }

    private static JsonNode response(List<JsonNode> lines, int id) {
        return lines.stream().filter(n -> n.has("id") && n.get("id").asInt() == id).findFirst().orElseThrow(() -> new AssertionError("no response " + id + " in " + lines));
    }

    @Test
    void pingAndUnknownMethod() throws Exception {
        List<JsonNode> lines = serve("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}", "{\"id\":3,\"method\":\"shutdown\"}");
        assertEquals("pong", response(lines, 1).get("result").asText());
        assertEquals(-32601, response(lines, 2).get("error").get("code").asInt());
        assertEquals("bye", response(lines, 3).get("result").asText());
    }

    /** Instance methods declared in an abstract class are tested through a concrete public subclass. */
    @Test
    void abstractClassMethodsGetAConcreteReceiver(@TempDir Path tmp) throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.AbstractTally").getProtectionDomain().getCodeSource().getLocation().toURI());
        String args = OM.writeValueAsString(List.of("--classes", samples.toString(), "--class", "cn.ac.ios.justin.samples.AbstractTally",
                "--tuples", "6", "--no-symbolic", "--java-version", "11", "--out", tmp.toString(), "--label", "abs"));
        List<JsonNode> lines = serve(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"generate\",\"params\":{\"args\":" + args + "}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\"}");
        JsonNode done = lines.stream().filter(n -> "justin/done".equals(n.path("method").asText())).findFirst().orElseThrow();
        assertEquals("done", done.get("params").get("state").asText(), done.toString());
        assertTrue(done.get("params").get("tests").asInt() >= 2, done.toString());
        String src = java.nio.file.Files.readString(tmp.resolve("abs/generated-tests/src/cn/ac/ios/justin/samples/AbstractTallyJustinTest.java"));
        assertTrue(src.contains("new AbstractTally.SimpleTally("), src);
        assertTrue(src.contains("instance.add(") && src.contains("instance.mean()"), src);
    }

    @Test
    void generateJobReportsProgressAndResult(@TempDir Path tmp) throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.Matrix").getProtectionDomain().getCodeSource().getLocation().toURI());
        String args = OM.writeValueAsString(List.of("--classes", samples.toString(), "--class", "cn.ac.ios.justin.samples.Matrix",
                "--strategies", "special", "--tuples", "4", "--no-symbolic", "--java-version", "11", "--out", tmp.toString(), "--label", "job"));
        List<JsonNode> lines = serve(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"generate\",\"params\":{\"args\":" + args + "}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"status\",\"params\":{\"jobId\":1}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"result\",\"params\":{\"jobId\":99}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\"}");
        assertEquals(1, response(lines, 1).get("result").get("jobId").asInt());
        assertTrue(List.of("queued", "running", "done").contains(response(lines, 2).get("result").get("state").asText()));
        assertEquals(-32602, response(lines, 3).get("error").get("code").asInt(), "unknown job is an invalid-params error");
        JsonNode done = lines.stream().filter(n -> "justin/done".equals(n.path("method").asText())).findFirst().orElseThrow();
        assertEquals("done", done.get("params").get("state").asText(), done.toString());
        assertTrue(done.get("params").get("tests").asInt() > 0);
        assertTrue(lines.stream().anyMatch(n -> "justin/progress".equals(n.path("method").asText()) && "class".equals(n.path("params").path("event").asText())));
        assertTrue(done.get("params").get("runDir").asText().endsWith("job"));
    }
}
