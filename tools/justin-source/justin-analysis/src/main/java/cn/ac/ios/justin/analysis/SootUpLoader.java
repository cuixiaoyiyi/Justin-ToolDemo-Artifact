package cn.ac.ios.justin.analysis;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.cache.provider.LRUCacheProvider;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.core.transform.BodyInterceptor;
import sootup.core.types.ClassType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * On-demand SootUp access. One loader = one JavaView over the target classpath plus lazily consulted
 * dependency locations. Nothing is resolved until a class or method body is requested, and the class
 * cache is LRU-bounded so memory stays proportional to the working set, not the project.
 */
public final class SootUpLoader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SootUpLoader.class);

    private final JavaView view;

    public SootUpLoader(List<Path> targetRoots, List<Path> dependencyRoots, int lruSize, List<BodyInterceptor> interceptors) {
        List<AnalysisInputLocation> locations = new ArrayList<>();
        for (Path p : targetRoots) locations.add(new JavaClassPathAnalysisInputLocation(p.toString(), SourceType.Application, interceptors));
        for (Path p : dependencyRoots) {
            // a missing dependency entry must not disable analysis of the target classes
            if (!java.nio.file.Files.exists(p)) { log.warn("dependency classpath entry does not exist, skipped: {}", p); continue; }
            locations.add(new JavaClassPathAnalysisInputLocation(p.toString(), SourceType.Library, interceptors));
        }
        this.view = new JavaView(locations, new LRUCacheProvider(lruSize));
    }

    /** Default interceptors of the bytecode frontend, LRU of 256 classes. */
    public static SootUpLoader withDefaults(List<Path> targetRoots, List<Path> dependencyRoots) {
        List<BodyInterceptor> defaults = new JavaClassPathAnalysisInputLocation(targetRoots.get(0).toString()).getBodyInterceptors();
        return new SootUpLoader(targetRoots, dependencyRoots, 256, defaults);
    }

    /** No body interceptors at all: raw Jimple, cheapest to build, enough for pattern matching. */
    public static SootUpLoader minimal(List<Path> targetRoots, List<Path> dependencyRoots) {
        return new SootUpLoader(targetRoots, dependencyRoots, 256, List.of());
    }

    public JavaView view() { return view; }

    public synchronized Optional<JavaSootClass> loadClass(String binaryName) {
        ClassType t = view.getIdentifierFactory().getClassType(binaryName);
        return view.getClass(t);
    }

    /** Resolves a Jimple signature (e.g. a callee) under the loader's lock. */
    public synchronized Optional<JavaSootMethod> loadMethod(MethodSignature sig) {
        try {
            return view.getMethod(sig);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public synchronized Optional<JavaSootMethod> loadMethod(MethodTarget m) {
        ClassType owner = view.getIdentifierFactory().getClassType(m.declaringClass());
        List<String> params = m.params().stream().map(Parameter::type).map(SootUpLoader::sootTypeName).collect(Collectors.toList());
        MethodSignature sig = view.getIdentifierFactory().getMethodSignature(owner, m.name(), sootTypeName(m.returnType()), params);
        try {
            return view.getMethod(sig);
        } catch (RuntimeException e) {
            log.warn("SootUp could not resolve {}: {}", m.id(), e.toString());
            return Optional.empty();
        }
    }

    public int cachedClasses() { return view.getCachedClassesCount(); }

    /** SootUp spells types as Java source names with '$' for nested classes and [] suffixes for arrays. */
    static String sootTypeName(TypeRef t) {
        return t.erasureName();
    }

    @Override public void close() {
        // JavaView holds no external resources; dropping the reference is enough.
    }
}
