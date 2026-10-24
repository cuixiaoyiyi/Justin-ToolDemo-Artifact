package cn.ac.ios.justin.llm;

/** Accumulated cost of the model calls of one run: wall time and tokens, as reported by the API. */
public final class LlmUsage {
    private long calls, millis, inputTokens, outputTokens, maxMillis;

    public synchronized void record(long elapsedMillis, long in, long out) {
        calls++;
        millis += elapsedMillis;
        inputTokens += in;
        outputTokens += out;
        if (elapsedMillis > maxMillis) maxMillis = elapsedMillis;
    }

    public synchronized long calls() { return calls; }
    public synchronized long millis() { return millis; }
    public synchronized long inputTokens() { return inputTokens; }
    public synchronized long outputTokens() { return outputTokens; }
    public synchronized long maxMillis() { return maxMillis; }
    public synchronized long averageMillis() { return calls == 0 ? 0 : millis / calls; }
}
