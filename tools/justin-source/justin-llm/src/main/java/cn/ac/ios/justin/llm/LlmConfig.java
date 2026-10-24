package cn.ac.ios.justin.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Where the model lives and how much it may be used. Loaded from a properties file that must stay
 * outside version control (keys!) with environment-variable fallbacks; the key is never logged or
 * written to reports.
 *
 * Properties: baseUrl, model, apiKey, maxCallsPerRun, maxTokensPerCall, cacheDir, excludePackages (comma separated), timeoutSeconds.
 * Environment: JUSTIN_LLM_BASE_URL, JUSTIN_LLM_MODEL, JUSTIN_LLM_API_KEY (or ANTHROPIC_API_KEY).
 */
public record LlmConfig(String baseUrl, String model, String apiKey, int maxCallsPerRun, int maxTokensPerCall, Path cacheDir,
                        List<String> excludePackages, int timeoutSeconds, boolean disableThinking) {

    /** Compatibility constructor: thinking left to the model's default. */
    public LlmConfig(String baseUrl, String model, String apiKey, int maxCallsPerRun, int maxTokensPerCall, Path cacheDir, List<String> excludePackages, int timeoutSeconds) {
        this(baseUrl, model, apiKey, maxCallsPerRun, maxTokensPerCall, cacheDir, excludePackages, timeoutSeconds, false);
    }

    public static final String DEFAULT_MODEL = "claude-opus-5";

    public static LlmConfig load(Path file, Path defaultCacheDir) throws IOException {
        Properties p = new Properties();
        if (file != null) {
            try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        }
        String baseUrl = first(p.getProperty("baseUrl"), System.getenv("JUSTIN_LLM_BASE_URL"), System.getenv("ANTHROPIC_BASE_URL"));
        String model = first(p.getProperty("model"), System.getenv("JUSTIN_LLM_MODEL"), DEFAULT_MODEL);
        String key = first(p.getProperty("apiKey"), System.getenv("JUSTIN_LLM_API_KEY"), System.getenv("ANTHROPIC_API_KEY"));
        if (key == null || key.isBlank()) throw new IOException("no LLM API key: set apiKey in " + file + " or JUSTIN_LLM_API_KEY / ANTHROPIC_API_KEY");
        int maxCalls = Integer.parseInt(p.getProperty("maxCallsPerRun", "300"));
        int maxTokens = Integer.parseInt(p.getProperty("maxTokensPerCall", "4000"));
        Path cache = p.getProperty("cacheDir") != null ? Path.of(p.getProperty("cacheDir")) : defaultCacheDir;
        List<String> exclude = p.getProperty("excludePackages") == null ? List.of()
                : List.of(p.getProperty("excludePackages").split("\\s*,\\s*"));
        int timeout = Integer.parseInt(p.getProperty("timeoutSeconds", "90"));
        // thinking=disabled: for models whose reasoning output would otherwise consume the token budget
        // (the answer here is a small JSON object; extended reasoning adds latency, not accuracy)
        boolean noThinking = "disabled".equalsIgnoreCase(p.getProperty("thinking", "default"));
        return new LlmConfig(baseUrl, model, key, maxCalls, maxTokens, cache, exclude, timeout, noThinking);
    }

    private static String first(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    /** For logs: never the key. */
    public String describe() {
        return (baseUrl == null ? "api.anthropic.com" : baseUrl) + " / " + model + " (max " + maxCallsPerRun + " calls, " + maxTokensPerCall + " tokens each)";
    }
}
