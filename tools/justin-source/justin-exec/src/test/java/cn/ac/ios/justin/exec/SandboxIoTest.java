package cn.ac.ios.justin.exec;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.Outcome;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SandboxIoTest {

    /** Fixture loaded into the sandbox from target/test-classes. */
    @SuppressWarnings("unused")
    public static final class IoFixture {
        public static int write(String name) throws IOException {
            Path p = Files.createTempFile("justin-io-test", name);
            Files.write(p, new byte[]{1});
            return 1;
        }
        public static void exit(int code) { System.exit(code); }
        /** Catches whatever the write throws and falls back, as jline does around its infocmp process. */
        public static int swallow(String name) {
            try { return write(name); } catch (Exception e) { return -1; }
        }
        public static String pure(String s) { return s.toUpperCase(); }
        public static long size(String s) throws IOException { return Files.size(Path.of(s)); } // reads are allowed
    }

    private static Path testClasses() {
        return Path.of(IoFixture.class.getProtectionDomain().getCodeSource().getLocation().getPath().replaceFirst("^/([A-Za-z]:)", "$1"));
    }

    private static MethodTarget target(String name, String desc, TypeRef ret, Parameter... ps) {
        return new MethodTarget(IoFixture.class.getName(), name, desc, 0x0009, List.of(), List.of(ps), ret, List.of(), List.of(), 1);
    }

    @Test
    void fileWritesAndExitAreBlockedPureCallsAreNot() throws Exception {
        try (Sandbox sb = new Sandbox(List.of(testClasses()), null, n -> false, true);
             Invoker inv = new Invoker(sb, 5000)) {
            Outcome w = inv.execute(new InputTuple(target("write", "(Ljava/lang/String;)I", TypeRef.INT,
                    new Parameter(0, "name", TypeRef.STRING, List.of())), null, List.of(new Value.StringValue("x")), Map.of(), "t"));
            assertInstanceOf(Outcome.Blocked.class, w, w.toString());
            assertTrue(((Outcome.Blocked) w).reason().contains("Files.createTempFile"));

            Outcome e = inv.execute(new InputTuple(target("exit", "(I)V", TypeRef.VOID,
                    new Parameter(0, "code", TypeRef.INT, List.of())), null, List.of(Value.PrimitiveValue.of(3)), Map.of(), "t"));
            assertInstanceOf(Outcome.Blocked.class, e, e.toString());

            Outcome p = inv.execute(new InputTuple(target("pure", "(Ljava/lang/String;)Ljava/lang/String;", TypeRef.STRING,
                    new Parameter(0, "s", TypeRef.STRING, List.of())), null, List.of(new Value.StringValue("ab")), Map.of(), "t"));
            assertInstanceOf(Outcome.Returned.class, p, p.toString());
        }
    }

    @Test
    void blockedCallSwallowedByTheSubjectIsStillBlocked() throws Exception {
        try (Sandbox sb = new Sandbox(List.of(testClasses()), null, n -> false, true);
             Invoker inv = new Invoker(sb, 5000)) {
            Outcome w = inv.execute(new InputTuple(target("swallow", "(Ljava/lang/String;)I", TypeRef.INT,
                    new Parameter(0, "name", TypeRef.STRING, List.of())), null, List.of(new Value.StringValue("x")), Map.of(), "t"));
            assertInstanceOf(Outcome.Blocked.class, w, w.toString());
            assertTrue(((Outcome.Blocked) w).reason().startsWith("swallowed: "), w.toString());

            Outcome p = inv.execute(new InputTuple(target("pure", "(Ljava/lang/String;)Ljava/lang/String;", TypeRef.STRING,
                    new Parameter(0, "s", TypeRef.STRING, List.of())), null, List.of(new Value.StringValue("ab")), Map.of(), "t"));
            assertInstanceOf(Outcome.Returned.class, p, p.toString());
        }
    }

    @Test
    void withoutBlockingTheWriteSucceeds() throws Exception {
        try (Sandbox sb = new Sandbox(List.of(testClasses()), null, n -> false, false);
             Invoker inv = new Invoker(sb, 5000)) {
            Outcome w = inv.execute(new InputTuple(target("write", "(Ljava/lang/String;)I", TypeRef.INT,
                    new Parameter(0, "name", TypeRef.STRING, List.of())), null, List.of(new Value.StringValue("y")), Map.of(), "t"));
            assertInstanceOf(Outcome.Returned.class, w, w.toString());
        }
    }
}
