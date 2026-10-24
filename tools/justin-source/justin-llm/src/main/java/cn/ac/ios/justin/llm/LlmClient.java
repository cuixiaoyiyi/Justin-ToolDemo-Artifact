package cn.ac.ios.justin.llm;

/** One completion: system prompt plus user prompt in, text out. Implementations must be thread-safe. */
public interface LlmClient {
    String complete(String system, String user) throws LlmException;

    class LlmException extends Exception {
        public LlmException(String msg, Throwable cause) { super(msg, cause); }
    }
}
