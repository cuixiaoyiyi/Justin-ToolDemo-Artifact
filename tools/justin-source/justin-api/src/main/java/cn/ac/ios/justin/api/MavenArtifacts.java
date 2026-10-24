package cn.ac.ios.justin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A subject given as a Maven artifact: coordinates or a link are turned into the jar, its sources jar
 * and the jars of its dependencies, downloaded into a local cache in repository layout.
 *
 * <p>Accepted forms: {@code group:artifact:version} (also {@code group:artifact} for the latest
 * release), a link into a repository in Maven layout (the jar, the pom or the version directory, on
 * Maven Central or a mirror or a Nexus / Artifactory instance), and artifact pages of
 * mvnrepository.com, central.sonatype.com and search.maven.org.
 *
 * <p>Dependency resolution is a deliberately small reading of the POM: parents, properties,
 * dependencyManagement with imported BOMs, exclusions, nearest-wins, compile and runtime scope
 * transitively, plus provided and optional dependencies of the artifact itself (its own classes need
 * them to load). Profiles, version ranges beyond their first bound and relocation are not
 * interpreted; a dependency that cannot be resolved is reported and skipped, which costs coverage in
 * the classes that use it but does not stop generation.
 */
public final class MavenArtifacts {
    private static final Logger log = LoggerFactory.getLogger(MavenArtifacts.class);

    public static final String CENTRAL = "https://repo1.maven.org/maven2";
    private static final String MIRROR = "https://maven.aliyun.com/repository/public";
    private static final int MAX_DEPENDENCIES = 300, MAX_DEPTH = 8;

    public record Coordinates(String groupId, String artifactId, String version, String repository) {
        public String gav() { return groupId + ":" + artifactId + ":" + version; }
    }

    /** @param sources the unpacked-or-not sources jar, null when the repository has none */
    public record Resolved(Coordinates coordinates, Path jar, Path sources, List<Path> dependencies, List<String> unresolved) {}

    private final List<String> repositories = new ArrayList<>();
    private final Path cache;
    private final Path localRepository;
    private final HttpClient http;
    private final Map<String, Pom> poms = new HashMap<>();

    /**
     * @param extraRepositories tried first, in order; then the repository of the link (if any), Maven Central and a mirror
     * @param cache             download directory in repository layout; null for {@code ~/.justin/repository}
     */
    public MavenArtifacts(List<String> extraRepositories, Path cache) {
        for (String r : extraRepositories) addRepository(r);
        String env = System.getenv("JUSTIN_MAVEN_REPO");
        if (env != null) for (String r : env.split(",")) addRepository(r.trim());
        Path home = Path.of(System.getProperty("user.home", "."));
        this.cache = cache != null ? cache : home.resolve(".justin").resolve("repository");
        this.localRepository = home.resolve(".m2").resolve("repository");
        HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20));
        ProxySelector proxy = proxyFromEnvironment();
        if (proxy != null) b.proxy(proxy);
        this.http = b.build();
    }

    private void addRepository(String r) {
        if (r == null || r.isBlank()) return;
        String clean = r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
        if (!repositories.contains(clean)) repositories.add(clean);
    }

    /** HTTPS_PROXY / HTTP_PROXY, unless the JVM already has proxy properties (which the default selector honours). */
    private static ProxySelector proxyFromEnvironment() {
        if (System.getProperty("https.proxyHost") != null || System.getProperty("http.proxyHost") != null) return null;
        for (String name : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            String v = System.getenv(name);
            if (v == null || v.isBlank()) continue;
            try {
                URI u = URI.create(v.contains("://") ? v : "http://" + v);
                if (u.getHost() != null) return ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80));
            } catch (IllegalArgumentException e) {
                // not a usable proxy address
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ links and coordinates

    /** Coordinates from {@code g:a[:v]}, a repository link or an artifact page; the version may still be null. */
    public static Coordinates parse(String link) {
        String s = link.trim();
        if (!s.contains("://")) {
            String[] p = s.split(":");
            if (p.length == 2) return new Coordinates(p[0], p[1], null, null);
            if (p.length == 3) return new Coordinates(p[0], p[1], p[2], null);
            if (p.length == 4) return new Coordinates(p[0], p[1], p[3], null);           // g:a:packaging:v
            if (p.length == 5) return new Coordinates(p[0], p[1], p[4], null);           // g:a:packaging:classifier:v
            throw new IllegalArgumentException("not Maven coordinates (group:artifact:version) or a link: " + link);
        }
        URI uri = URI.create(s.replace(" ", "%20"));
        String host = uri.getHost() == null ? "" : uri.getHost();
        List<String> seg = new ArrayList<>();
        for (String x : uri.getPath().split("/")) if (!x.isEmpty()) seg.add(x);
        // artifact pages: .../artifact/<group>/<artifact>[/<version>[/<packaging>]]
        int page = seg.indexOf("artifact");
        if (page >= 0 && (host.contains("mvnrepository.com") || host.contains("sonatype.com") || host.contains("search.maven.org")) && seg.size() >= page + 3) {
            return new Coordinates(seg.get(page + 1), seg.get(page + 2), seg.size() > page + 3 ? seg.get(page + 3) : null, null);
        }
        // repository layout: <root>/<group as path>/<artifact>/<version>[/<file>]
        int root = repositoryRootLength(seg);
        List<String> rest = new ArrayList<>(seg.subList(root, seg.size()));
        if (!rest.isEmpty() && rest.get(rest.size() - 1).contains(".") && (rest.get(rest.size() - 1).endsWith(".jar") || rest.get(rest.size() - 1).endsWith(".pom")
                || rest.get(rest.size() - 1).endsWith(".xml") || rest.get(rest.size() - 1).endsWith(".module"))) {
            String file = rest.remove(rest.size() - 1);
            if (file.equals("maven-metadata.xml")) rest.add(null);        // artifact directory: no version segment
        }
        String version = null;
        if (!rest.isEmpty() && rest.get(rest.size() - 1) == null) rest.remove(rest.size() - 1);
        else if (rest.size() >= 3 && !rest.get(rest.size() - 1).isEmpty() && Character.isDigit(rest.get(rest.size() - 1).charAt(0))) version = rest.remove(rest.size() - 1);
        if (rest.size() < 2) throw new IllegalArgumentException("cannot read group and artifact from " + link);
        String artifact = rest.remove(rest.size() - 1);
        String base = uri.getScheme() + "://" + uri.getAuthority() + (root == 0 ? "" : "/" + String.join("/", seg.subList(0, root)));
        return new Coordinates(String.join(".", rest), artifact, version, base);
    }

    /** How many leading path segments belong to the repository itself rather than to the group. */
    private static int repositoryRootLength(List<String> seg) {
        for (int i = 0; i < seg.size(); i++) {
            String x = seg.get(i);
            if (x.equals("maven2") || x.equals("maven") && i == 0) return i + 1;
            if ((x.equals("repository") || x.equals("artifactory") || x.equals("repositories") || x.equals("groups")) && i + 1 < seg.size()) return i + 2;
        }
        return 0;
    }

    // ------------------------------------------------------------------ resolution

    public Resolved resolve(String link, boolean withDependencies) throws IOException {
        Coordinates c = parse(link);
        if (c.repository() != null) repositories.add(0, c.repository().endsWith("/") ? c.repository().substring(0, c.repository().length() - 1) : c.repository());
        addRepository(CENTRAL);
        addRepository(MIRROR);
        if (c.version() == null) c = new Coordinates(c.groupId(), c.artifactId(), latestVersion(c), c.repository());
        log.info("maven artifact {} (repositories: {})", c.gav(), repositories);
        Path jar = fetch(c.groupId(), c.artifactId(), c.version(), null, "jar");
        if (jar == null) {
            Pom pom = pom(c.groupId(), c.artifactId(), c.version());
            String packaging = pom == null ? null : pom.packaging;
            throw new IOException("no jar for " + c.gav() + " in " + repositories + (packaging != null && !packaging.equals("jar") ? " (packaging is " + packaging + ")" : ""));
        }
        Path sources = fetch(c.groupId(), c.artifactId(), c.version(), "sources", "jar");
        if (sources == null) log.info("{} has no sources jar: the report will have no source view", c.gav());
        List<Path> deps = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        if (withDependencies) dependencies(c, deps, unresolved);
        return new Resolved(c, jar, sources, deps, unresolved);
    }

    private record Dep(String g, String a, String v, String classifier, Set<String> exclusions, int depth) {}

    private void dependencies(Coordinates root, List<Path> out, List<String> unresolved) {
        Deque<Dep> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        seen.add(root.groupId() + ":" + root.artifactId());
        queue.add(new Dep(root.groupId(), root.artifactId(), root.version(), null, Set.of(), 0));
        while (!queue.isEmpty() && out.size() < MAX_DEPENDENCIES) {
            Dep d = queue.poll();
            Pom pom = pom(d.g(), d.a(), d.v());
            if (pom == null) continue;
            for (Pom.Dependency x : pom.dependencies()) {
                boolean rootLevel = d.depth() == 0;
                String scope = x.scope == null ? "compile" : x.scope;
                if (!(scope.equals("compile") || scope.equals("runtime") || rootLevel && scope.equals("provided"))) continue;
                if (x.optional && !rootLevel) continue;
                if (x.type != null && !x.type.equals("jar") && !x.type.equals("bundle") && !x.type.equals("maven-plugin")) continue;
                if (d.exclusions().contains(x.g + ":" + x.a) || d.exclusions().contains(x.g + ":*") || d.exclusions().contains("*:*")) continue;
                if (!seen.add(x.g + ":" + x.a)) continue;      // nearest wins: breadth-first, first one in
                if (x.v == null || x.v.contains("${")) { unresolved.add(x.g + ":" + x.a + " (no version)"); continue; }
                Path jar = fetch(x.g, x.a, x.v, x.classifier, "jar");
                if (jar == null) { unresolved.add(x.g + ":" + x.a + ":" + x.v); continue; }
                out.add(jar);
                if (d.depth() + 1 < MAX_DEPTH) {
                    Set<String> ex = new HashSet<>(d.exclusions());
                    ex.addAll(x.exclusions);
                    queue.add(new Dep(x.g, x.a, x.v, x.classifier, ex, d.depth() + 1));
                }
            }
        }
    }

    private String latestVersion(Coordinates c) throws IOException {
        for (String repo : repositories) {
            try {
                byte[] xml = get(repo + "/" + c.groupId().replace('.', '/') + "/" + c.artifactId() + "/maven-metadata.xml");
                if (xml == null) continue;
                Document doc = parseXml(xml);
                for (String tag : List.of("release", "latest")) {
                    NodeList n = doc.getElementsByTagName(tag);
                    if (n.getLength() > 0 && !n.item(0).getTextContent().isBlank()) return n.item(0).getTextContent().trim();
                }
                NodeList versions = doc.getElementsByTagName("version");
                if (versions.getLength() > 0) return versions.item(versions.getLength() - 1).getTextContent().trim();
            } catch (IOException | RuntimeException e) {
                log.debug("no metadata in {}: {}", repo, e.toString());
            }
        }
        throw new IOException("no version given and no maven-metadata.xml found for " + c.groupId() + ":" + c.artifactId());
    }

    // ------------------------------------------------------------------ files

    /** The file from the cache, from ~/.m2, or downloaded into the cache; null when no repository has it. */
    private Path fetch(String g, String a, String v, String classifier, String ext) {
        String name = a + "-" + v + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
        String rel = g.replace('.', '/') + "/" + a + "/" + v + "/" + name;
        Path cached = cache.resolve(rel);
        if (Files.isRegularFile(cached)) return cached;
        Path local = localRepository.resolve(rel);
        if (Files.isRegularFile(local)) return local;
        for (String repo : repositories) {
            try {
                byte[] bytes = get(repo + "/" + rel);
                if (bytes == null) continue;
                Files.createDirectories(cached.getParent());
                Path tmp = Files.createTempFile(cached.getParent(), name, ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
                log.info("downloaded {} ({} KB) from {}", name, bytes.length / 1024, repo);
                return cached;
            } catch (IOException | RuntimeException e) {
                log.debug("{} not from {}: {}", name, repo, e.toString());
            }
        }
        return null;
    }

    private byte[] get(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("User-Agent", "justin").GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) return null;
                return in.readAllBytes();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + url);
        }
    }

    // ------------------------------------------------------------------ POM

    /** The part of a POM needed here, with parents and imported BOMs folded in. */
    private static final class Pom {
        static final class Dependency {
            String g, a, v, scope, type, classifier;
            boolean optional;
            final Set<String> exclusions = new LinkedHashSet<>();
        }
        String groupId, artifactId, version, packaging;
        final Map<String, String> properties = new LinkedHashMap<>();
        final Map<String, Dependency> managed = new LinkedHashMap<>();
        final List<Dependency> declared = new ArrayList<>();

        String interpolate(String s) {
            if (s == null) return null;
            String out = s;
            for (int guard = 0; guard < 10 && out.contains("${"); guard++) {
                int from = out.indexOf("${"), to = out.indexOf('}', from);
                if (to < 0) break;
                String key = out.substring(from + 2, to);
                String value = switch (key) {
                    case "project.version", "pom.version", "version" -> version;
                    case "project.groupId", "pom.groupId", "groupId" -> groupId;
                    case "project.artifactId", "pom.artifactId", "artifactId" -> artifactId;
                    default -> properties.get(key);
                };
                if (value == null) break;
                out = out.substring(0, from) + value + out.substring(to + 1);
            }
            return out;
        }

        /** Declared dependencies with versions, scopes and exclusions completed from dependencyManagement. */
        List<Dependency> dependencies() {
            List<Dependency> out = new ArrayList<>();
            for (Dependency d : declared) {
                Dependency r = new Dependency();
                r.g = interpolate(d.g); r.a = interpolate(d.a); r.classifier = interpolate(d.classifier); r.type = interpolate(d.type);
                Dependency m = managed.get(r.g + ":" + r.a);
                r.v = interpolate(d.v != null ? d.v : m == null ? null : m.v);
                r.scope = d.scope != null ? d.scope : m == null ? null : m.scope;
                r.optional = d.optional;
                r.exclusions.addAll(d.exclusions);
                if (m != null) r.exclusions.addAll(m.exclusions);
                if (r.v != null && (r.v.startsWith("[") || r.v.startsWith("("))) {
                    // a range: its first concrete bound
                    String inner = r.v.substring(1, r.v.length() - 1);
                    String first = inner.split(",")[0].trim();
                    r.v = first.isEmpty() && inner.contains(",") ? inner.split(",")[1].trim() : first;
                    if (r.v.isEmpty()) r.v = null;
                }
                out.add(r);
            }
            return out;
        }
    }

    private Pom pom(String g, String a, String v) {
        String key = g + ":" + a + ":" + v;
        if (poms.containsKey(key)) return poms.get(key);
        poms.put(key, null);        // cycles end here
        Pom pom = null;
        try {
            Path file = fetch(g, a, v, null, "pom");
            if (file != null) pom = readPom(Files.readAllBytes(file), 0);
        } catch (IOException | RuntimeException e) {
            log.debug("cannot read the pom of {}: {}", key, e.toString());
        }
        poms.put(key, pom);
        return pom;
    }

    private Pom readPom(byte[] xml, int depth) throws IOException {
        Element project = parseXml(xml).getDocumentElement();
        Pom pom = new Pom();
        Element parent = child(project, "parent");
        Pom parentPom = null;
        if (parent != null && depth < 12) {
            parentPom = pom(text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"));
        }
        if (parentPom != null) {
            pom.properties.putAll(parentPom.properties);
            pom.managed.putAll(parentPom.managed);
            pom.declared.addAll(parentPom.declared);
            pom.groupId = parentPom.groupId;
            pom.version = parentPom.version;
        }
        if (parent != null) {
            if (pom.groupId == null) pom.groupId = text(parent, "groupId");
            if (pom.version == null) pom.version = text(parent, "version");
            pom.properties.put("project.parent.version", text(parent, "version"));
            pom.properties.put("project.parent.groupId", text(parent, "groupId"));
        }
        if (text(project, "groupId") != null) pom.groupId = text(project, "groupId");
        if (text(project, "version") != null) pom.version = text(project, "version");
        pom.artifactId = text(project, "artifactId");
        pom.packaging = text(project, "packaging");
        Element props = child(project, "properties");
        if (props != null) for (Element e : children(props)) pom.properties.put(e.getTagName(), e.getTextContent().trim());

        Element mgmt = child(project, "dependencyManagement");
        if (mgmt != null && child(mgmt, "dependencies") != null) {
            for (Element e : children(child(mgmt, "dependencies"))) {
                Pom.Dependency d = dependency(e);
                if ("import".equals(d.scope) && "pom".equals(d.type)) {
                    Pom bom = pom(pom.interpolate(d.g), pom.interpolate(d.a), pom.interpolate(d.v));
                    if (bom != null) for (var m : bom.managed.entrySet()) pom.managed.putIfAbsent(m.getKey(), resolved(bom, m.getValue()));
                } else {
                    pom.managed.put(pom.interpolate(d.g) + ":" + pom.interpolate(d.a), d);
                }
            }
        }
        // managed versions are interpolated in the POM that declares them, so they survive inheritance
        for (var m : pom.managed.entrySet()) m.setValue(resolved(pom, m.getValue()));
        Element deps = child(project, "dependencies");
        if (deps != null) for (Element e : children(deps)) pom.declared.add(dependency(e));
        return pom;
    }

    private static Pom.Dependency resolved(Pom owner, Pom.Dependency d) {
        Pom.Dependency r = new Pom.Dependency();
        r.g = owner.interpolate(d.g); r.a = owner.interpolate(d.a); r.type = d.type; r.classifier = d.classifier; r.scope = d.scope; r.optional = d.optional;
        String v = owner.interpolate(d.v);
        r.v = v;
        r.exclusions.addAll(d.exclusions);
        return r;
    }

    private static Pom.Dependency dependency(Element e) {
        Pom.Dependency d = new Pom.Dependency();
        d.g = text(e, "groupId"); d.a = text(e, "artifactId"); d.v = text(e, "version");
        d.scope = text(e, "scope"); d.type = text(e, "type"); d.classifier = text(e, "classifier");
        d.optional = "true".equals(text(e, "optional"));
        Element ex = child(e, "exclusions");
        if (ex != null) for (Element x : children(ex)) d.exclusions.add(text(x, "groupId") + ":" + text(x, "artifactId"));
        return d;
    }

    private static Document parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("unreadable XML: " + e.getMessage());
        }
    }

    private static Element child(Element parent, String tag) {
        for (Element e : children(parent)) if (e.getTagName().equals(tag)) return e;
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) out.add(e);
        return out;
    }

    private static String text(Element parent, String tag) {
        Element e = child(parent, tag);
        if (e == null) return null;
        String t = e.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }
}
