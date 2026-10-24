package cn.ac.ios.justin.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.time.Duration;

/**
 * Messages API through the official SDK. The base URL is configurable so Anthropic-compatible
 * gateways (e.g. DeepSeek's /anthropic endpoint) work with the same code. The request stays minimal
 * (model, max_tokens, system, one user turn) because gateways reject fields they do not implement.
 */
public final class AnthropicLlmClient implements LlmClient {
    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final boolean disableThinking;
    private final LlmUsage usage = new LlmUsage();

    public LlmUsage usage() { return usage; }

    public AnthropicLlmClient(LlmConfig cfg) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(cfg.apiKey()).timeout(Duration.ofSeconds(cfg.timeoutSeconds())).maxRetries(2);
        if (cfg.baseUrl() != null) b.baseUrl(cfg.baseUrl());
        this.client = b.build();
        this.model = cfg.model();
        this.maxTokens = cfg.maxTokensPerCall();
        this.disableThinking = cfg.disableThinking();
    }

    @Override
    public String complete(String system, String user) throws LlmException {
        try {
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(system)
                    .addUserMessage(user);
            if (disableThinking) pb.thinking(com.anthropic.models.messages.ThinkingConfigDisabled.builder().build());
            MessageCreateParams params = pb.build();
            long t0 = System.currentTimeMillis();
            Message m = client.messages().create(params);
            long elapsed = System.currentTimeMillis() - t0;
            long in = 0, out = 0;
            try { in = m.usage().inputTokens(); out = m.usage().outputTokens(); } catch (RuntimeException ignored) { }
            usage.record(elapsed, in, out);
            StringBuilder sb = new StringBuilder();
            m.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
            return sb.toString();
        } catch (RuntimeException e) {
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }
}
