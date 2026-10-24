package cn.ac.ios.justin.cli;

import cn.ac.ios.justin.api.Engine;
import cn.ac.ios.justin.api.GenerationListener;
import cn.ac.ios.justin.api.GenerationRequest;
import cn.ac.ios.justin.api.GenerationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-RPC 2.0 front-end over a line-delimited stream (stdin/stdout or one TCP connection), the
 * process IDE plugins and other tools drive instead of the CLI (plan: justin-daemon). One request or
 * notification per line; responses and progress notifications are written one per line.
 *
 * <pre>
 * {"jsonrpc":"2.0","id":1,"method":"generate","params":{"args":["--classes","app.jar","--out","out","--run"]}}
 *   -> {"jsonrpc":"2.0","id":1,"result":{"jobId":1}}
 *   -> {"jsonrpc":"2.0","method":"justin/progress","params":{"jobId":1,"event":"phase","name":"scan"}}
 *   -> {"jsonrpc":"2.0","method":"justin/progress","params":{"jobId":1,"event":"class","name":"a.B","index":1,"total":3}}
 *   -> {"jsonrpc":"2.0","method":"justin/done","params":{"jobId":1,"state":"done","runDir":"...","tests":42}}
 * {"jsonrpc":"2.0","id":2,"method":"result","params":{"jobId":1}}   -> the run's report.json content
 * {"jsonrpc":"2.0","id":3,"method":"status","params":{"jobId":1}}   -> {"state":"done","runDir":...}
 * {"jsonrpc":"2.0","id":4,"method":"cancel","params":{"jobId":1}}
 * {"jsonrpc":"2.0","id":5,"method":"ping"}                           -> "pong"
 * {"jsonrpc":"2.0","id":6,"method":"shutdown"}                       -> waits for running jobs, then exits the loop
 * </pre>
 *
 * The {@code args} of {@code generate} are exactly the options of {@code justin generate}, so the two
 * front-ends cannot drift apart. Jobs run one after another on a worker thread.
 */
public final class DaemonServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DaemonServer.class);

    private final BufferedReader in;
    private final PrintWriter out;
    private final ObjectMapper om = new ObjectMapper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "justin-daemon-job"); t.setDaemon(true); return t; });
    private final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile boolean shutdown;

    static final class Job {
        final int id;
        final List<String> args;
        volatile String state = "queued";
        volatile boolean cancelled;
        volatile GenerationResult result;
        volatile String error;
        volatile String runDir;

        Job(int id, List<String> args) { this.id = id; this.args = args; }
    }

    public DaemonServer(InputStream in, OutputStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    }

    /** Serves requests until the stream ends or {@code shutdown} was called; running jobs are awaited on shutdown. */
    @Override public void run() {
        try {
            String line;
            while (!shutdown && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode req;
                try {
                    req = om.readTree(line);
                } catch (IOException e) {
                    write(error(null, -32700, "parse error: " + e.getMessage()));
                    continue;
                }
                JsonNode response = handle(req);
                if (response != null) write(response);
            }
        } catch (IOException e) {
            log.warn("daemon input closed: {}", e.toString());
        } finally {
            worker.shutdown();
            try {
                worker.awaitTermination(shutdown ? 30 : 1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Handles one request; returns null for notifications (no id). */
    JsonNode handle(JsonNode req) {
        JsonNode id = req.get("id");
        String method = req.path("method").asText(null);
        JsonNode params = req.path("params");
        if (method == null) return id == null ? null : error(id, -32600, "missing method");
        try {
            Object result;
            switch (method) {
                case "ping" -> result = "pong";
                case "generate" -> result = generate(params);
                case "status" -> result = status(job(params));
                case "result" -> {
                    Job j = job(params);
                    if (j.result == null) return error(id, -32001, "job " + j.id + " is " + j.state);
                    result = om.valueToTree(j.result);
                }
                case "cancel" -> {
                    Job j = job(params);
                    j.cancelled = true;
                    result = status(j);
                }
                case "list" -> {
                    ArrayNode arr = om.createArrayNode();
                    jobs.values().stream().sorted((a, b) -> Integer.compare(a.id, b.id)).forEach(j -> arr.add(status(j)));
                    result = arr;
                }
                case "shutdown" -> {
                    shutdown = true;
                    result = "bye";
                }
                default -> { return id == null ? null : error(id, -32601, "unknown method " + method); }
            }
            if (id == null) return null;
            ObjectNode resp = om.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id);
            resp.set("result", result instanceof JsonNode n ? n : om.valueToTree(result));
            return resp;
        } catch (IllegalArgumentException e) {
            return id == null ? null : error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("daemon request failed", e);
            return id == null ? null : error(id, -32603, e.toString());
        }
    }

    private ObjectNode generate(JsonNode params) {
        JsonNode argsNode = params.path("args");
        if (!argsNode.isArray() || argsNode.isEmpty()) throw new IllegalArgumentException("params.args must be the option list of 'justin generate'");
        List<String> args = new ArrayList<>();
        for (JsonNode a : argsNode) args.add(a.asText());
        Job job = new Job(ids.incrementAndGet(), args);
        jobs.put(job.id, job);
        worker.submit(() -> runJob(job));
        ObjectNode r = om.createObjectNode();
        r.put("jobId", job.id);
        return r;
    }

    private void runJob(Job job) {
        if (job.cancelled) { job.state = "cancelled"; done(job); return; }
        job.state = "running";
        try {
            GenerateCommand cmd = new GenerateCommand();
            new CommandLine(cmd).parseArgs(job.args.toArray(new String[0]));
            GenerationRequest req = cmd.buildRequest();
            job.runDir = req.runDir().toString();
            GenerationListener listener = new GenerationListener() {
                @Override public void phase(String name) { progress(job, "phase", n -> n.put("name", name)); }
                @Override public void classStarted(String name, int index, int total) { progress(job, "class", n -> { n.put("name", name); n.put("index", index); n.put("total", total); }); }
                @Override public void classFinished(String name, int tests) { progress(job, "classDone", n -> { n.put("name", name); n.put("tests", tests); }); }
                @Override public void warning(String message) { progress(job, "warning", n -> n.put("message", message)); }
                @Override public boolean isCancelled() { return job.cancelled; }
            };
            GenerationResult r = new Engine().run(req, listener);
            job.result = r;
            job.runDir = r.runDir();
            job.state = job.cancelled ? "cancelled" : "done";
        } catch (Exception | Error e) {
            job.error = e.toString();
            job.state = "failed";
            log.warn("job {} failed", job.id, e);
        }
        done(job);
    }

    private void progress(Job job, String event, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode p = om.createObjectNode();
        p.put("jobId", job.id);
        p.put("event", event);
        fill.accept(p);
        notify("justin/progress", p);
    }

    private void done(Job job) {
        ObjectNode p = status(job);
        if (job.result != null) {
            p.put("tests", job.result.totalTests());
            if (job.result.coverage() != null) {
                p.put("lineCoverage", job.result.coverage().lines().ratio());
                p.put("branchCoverage", job.result.coverage().branches().ratio());
            }
        }
        notify("justin/done", p);
    }

    private ObjectNode status(Job j) {
        ObjectNode s = om.createObjectNode();
        s.put("jobId", j.id);
        s.put("state", j.state);
        if (j.runDir != null) s.put("runDir", j.runDir);
        if (j.error != null) s.put("error", j.error);
        return s;
    }

    private Job job(JsonNode params) {
        JsonNode id = params.path("jobId");
        if (!id.isInt()) throw new IllegalArgumentException("params.jobId required");
        Job j = jobs.get(id.asInt());
        if (j == null) throw new IllegalArgumentException("no job " + id.asInt());
        return j;
    }

    private void notify(String method, JsonNode params) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        n.set("params", params);
        write(n);
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode resp = om.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id == null) resp.putNull("id"); else resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return resp;
    }

    private void write(JsonNode node) {
        synchronized (out) {
            out.println(node.toString());
            out.flush();
        }
    }
}
