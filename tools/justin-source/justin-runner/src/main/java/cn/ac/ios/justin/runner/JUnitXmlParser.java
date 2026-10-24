package cn.ac.ios.justin.runner;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reads the legacy XML reports (TEST-*.xml) the console launcher writes into --reports-dir. */
public final class JUnitXmlParser {

    public enum Status { PASSED, FAILED, ERROR, SKIPPED }

    /** exceptionType and stackTrace are null unless the test failed or errored. */
    public record TestResult(String className, String methodName, Status status, double seconds, String message, String exceptionType, String stackTrace) {}

    public record Summary(List<TestResult> tests) {
        public long count(Status s) { return tests.stream().filter(t -> t.status() == s).count(); }
        public int total() { return tests.size(); }
    }

    private static final int MAX_STACK_LINES = 25;

    private JUnitXmlParser() {}

    public static Summary parse(Path reportsDir) throws IOException {
        List<TestResult> out = new ArrayList<>();
        if (!Files.isDirectory(reportsDir)) return new Summary(out);
        try (Stream<Path> files = Files.list(reportsDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (n.startsWith("TEST-") && n.endsWith(".xml")) parseFile(f, out);
            }
        }
        return new Summary(out);
    }

    private static void parseFile(Path f, List<TestResult> out) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder().parse(f.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                String cls = tc.getAttribute("classname");
                String name = tc.getAttribute("name");
                int paren = name.indexOf('(');
                if (paren > 0) name = name.substring(0, paren);
                double secs = parseDouble(tc.getAttribute("time"));
                Status st = Status.PASSED;
                String msg = null, type = null, stack = null;
                Element problem = firstChild(tc, "failure");
                if (problem != null) st = Status.FAILED;
                else { problem = firstChild(tc, "error"); if (problem != null) st = Status.ERROR; }
                if (problem != null) {
                    msg = problem.getAttribute("message");
                    type = problem.getAttribute("type");
                    stack = trimStack(problem.getTextContent());
                } else if (firstChild(tc, "skipped") != null) {
                    st = Status.SKIPPED;
                }
                out.add(new TestResult(cls, name, st, secs, msg, type, stack));
            }
        } catch (Exception e) {
            throw new IOException("cannot parse " + f + ": " + e.getMessage(), e);
        }
    }

    private static Element firstChild(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    /** Keeps the head of the trace (the interesting frames) and drops JUnit/reflection noise at the tail. */
    static String trimStack(String text) {
        if (text == null) return null;
        String[] lines = text.strip().split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String l : lines) {
            String t = l.strip();
            if (t.startsWith("at org.junit.") || t.startsWith("at java.base/jdk.internal.reflect") || t.startsWith("at java.base/java.lang.reflect")
                    || t.startsWith("at org.opentest4j") || t.startsWith("at java.base/java.util.ArrayList.forEach")) continue;
            sb.append(t).append('\n');
            if (++kept >= MAX_STACK_LINES) { sb.append("..."); break; }
        }
        return sb.toString().strip();
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (RuntimeException e) { return 0; }
    }
}
