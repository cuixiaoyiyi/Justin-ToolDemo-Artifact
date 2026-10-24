package cn.ac.ios.justin.exec;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Class loading for the subject. The loader's parent is the platform loader, so subject classes never
 * see Justin's own classes or its dependencies, and each Sandbox has independent static state.
 * Classes accepted by the instrumentation predicate are loaded with JaCoCo probes so coverage can
 * be drained per invocation; with blockIo every non-JDK class gets side-effecting JDK calls rewritten
 * to throw (see IoInterceptor).
 */
public final class Sandbox implements AutoCloseable {
    private final InstrumentingLoader loader;
    private final CoverageProbe probe;
    private final List<Path> classpath;
    private final Predicate<String> instrument;
    private final boolean blockIo;

    public Sandbox(List<Path> classpath) { this(classpath, null, n -> false, false); }

    public Sandbox(List<Path> classpath, CoverageProbe probe, Predicate<String> instrument, boolean blockIo) {
        URL[] urls = classpath.stream().map(p -> {
            try { return p.toUri().toURL(); } catch (MalformedURLException e) { throw new IllegalArgumentException(e); }
        }).toArray(URL[]::new);
        this.probe = probe;
        this.classpath = classpath;
        this.instrument = instrument;
        this.blockIo = blockIo;
        if (probe != null) probe.start();
        this.loader = new InstrumentingLoader(urls, probe, instrument, blockIo);
    }

    /**
     * A new sandbox with the same classpath, probe runtime and settings but a fresh class loader: used
     * after an invocation was stopped or ran out of memory, since such an event can leave class
     * initializers or static state of the subject in a broken condition that would poison every later
     * invocation. The probe keeps its execution data (probe ids are per class, not per loader).
     */
    public Sandbox recreate() {
        return new Sandbox(classpath, probe, instrument, blockIo);
    }

    public ClassLoader classLoader() { return loader; }

    public Class<?> load(String binaryName) throws ClassNotFoundException {
        return Class.forName(binaryName, true, loader);
    }

    public CoverageProbe probe() { return probe; }

    @Override public void close() {
        try { loader.close(); } catch (Exception ignored) { }
    }

    private static final class InstrumentingLoader extends URLClassLoader {
        private final CoverageProbe probe;
        private final Predicate<String> instrument;
        private final boolean blockIo;

        InstrumentingLoader(URL[] urls, CoverageProbe probe, Predicate<String> instrument, boolean blockIo) {
            super("justin-sandbox", urls, ClassLoader.getPlatformClassLoader());
            this.probe = probe;
            this.instrument = instrument;
            this.blockIo = blockIo;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(SandboxSupport.class.getName())) return defineSupport();
            boolean cover = probe != null && instrument.test(name);
            boolean block = blockIo && IoInterceptor.applies(name);
            if (!cover && !block) return super.findClass(name);
            String res = name.replace('.', '/') + ".class";
            // getResourceAsStream would go through the process-wide JarURLConnection cache, whose JarFile
            // another (closing) sandbox loader can close underneath us: read through an uncached connection
            URL url = findResource(res);
            if (url == null) throw new ClassNotFoundException(name);
            try {
                java.net.URLConnection conn = url.openConnection();
                conn.setUseCaches(false);
                byte[] bytes;
                try (InputStream in = conn.getInputStream()) { bytes = in.readAllBytes(); }
                if (block) bytes = IoInterceptor.rewrite(bytes);
                if (cover) bytes = probe.instrument(bytes, name);
                ensurePackage(name);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        /** Copies SandboxSupport's bytes from Justin's own loader into this one. */
        private Class<?> defineSupport() throws ClassNotFoundException {
            String res = SandboxSupport.class.getName().replace('.', '/') + ".class";
            try (InputStream in = SandboxSupport.class.getClassLoader().getResourceAsStream(res)) {
                if (in == null) throw new ClassNotFoundException(SandboxSupport.class.getName());
                byte[] bytes = in.readAllBytes();
                ensurePackage(SandboxSupport.class.getName());
                return defineClass(SandboxSupport.class.getName(), bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(SandboxSupport.class.getName(), e);
            }
        }

        private void ensurePackage(String name) {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) return;
            String pkg = name.substring(0, dot);
            if (getDefinedPackage(pkg) == null) {
                try { definePackage(pkg, null, null, null, null, null, null, null); } catch (IllegalArgumentException ignored) { }
            }
        }
    }
}
