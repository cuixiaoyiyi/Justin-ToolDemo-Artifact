package cn.ac.ios.justin.scan;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassScannerTest {

    /** A small generic class compiled alongside the tests, used as the scan subject. */
    @SuppressWarnings("unused")
    public static class Box<T extends Comparable<T>> {
        private final T value;
        public Box(T value) { this.value = value; }
        public T get() { return value; }
        public <U> Map<T, U> pairWith(U other, List<? extends U> more) { return Map.of(); }
        public static int twice(int x) { return 2 * x; }
        @Deprecated public void old() {}
    }

    private static ClassTarget scan(Class<?> c) throws IOException {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(in, res);
            return ClassScanner.scan(in.readAllBytes());
        }
    }

    @Test
    void readsGenericHeaderAndMethods() throws IOException {
        ClassTarget ct = scan(Box.class);
        assertEquals(ClassScannerTest.class.getName() + "$Box", ct.binaryName());
        assertEquals("T", ct.typeParams().get(0).name());
        assertEquals("java.lang.Comparable<T>", ct.typeParams().get(0).bounds().get(0).toSource());
        assertTrue(ct.isNested());

        MethodTarget get = ct.methods().stream().filter(m -> m.name().equals("get")).findFirst().orElseThrow();
        assertEquals("T", get.returnType().toSource());
        assertEquals("java.lang.Comparable", get.returnType().erasureName()); // bound carried over from the class header

        MethodTarget pair = ct.methods().stream().filter(m -> m.name().equals("pairWith")).findFirst().orElseThrow();
        assertEquals(List.of("U"), pair.typeParams().stream().map(v -> v.name()).toList());
        assertEquals("java.util.Map<T, U>", pair.returnType().toSource());
        assertEquals("java.util.List<? extends U>", pair.params().get(1).type().toSource());
        // parameter names come from the LocalVariableTable (compiled with -g by default)
        assertEquals("other", pair.params().get(0).name());

        MethodTarget twice = ct.methods().stream().filter(m -> m.name().equals("twice")).findFirst().orElseThrow();
        assertTrue(twice.isStatic());
        assertEquals("int", twice.params().get(0).type().toSource());
        assertTrue(twice.firstLine() > 0);

        MethodTarget old = ct.methods().stream().filter(m -> m.name().equals("old")).findFirst().orElseThrow();
        assertEquals("java.lang.Deprecated", old.annotations().get(0).binaryName());
    }
}
