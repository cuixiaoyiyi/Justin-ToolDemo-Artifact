package cn.ac.ios.justin.samples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Methods with side effects the sandbox must refuse to execute. */
public final class SideEffects {
    private SideEffects() {}

    public static int writeLog(String name, String text) throws IOException {
        Path p = Paths.get(System.getProperty("java.io.tmpdir"), "justin-" + name + ".log");
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return text.length();
    }

    public static void quit(int code) {
        if (code != 0) System.exit(code);
    }

    public static Thread spawn(Runnable r) {
        Thread t = new Thread(r);
        t.start();
        return t;
    }

    public static String pure(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
