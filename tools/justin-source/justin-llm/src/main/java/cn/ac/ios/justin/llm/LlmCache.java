package cn.ac.ios.justin.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/** Responses keyed by a hash of (model, prompt): the same method never costs twice and runs are reproducible. */
public final class LlmCache {
    private final Path dir;

    public LlmCache(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public String key(String model, String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((model + "\n" + prompt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) throws IOException {
        Path f = dir.resolve(key + ".txt");
        return Files.exists(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
    }

    public void put(String key, String response) throws IOException {
        Files.writeString(dir.resolve(key + ".txt"), response, StandardCharsets.UTF_8);
    }
}
