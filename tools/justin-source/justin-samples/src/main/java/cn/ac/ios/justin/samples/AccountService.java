package cn.ac.ios.justin.samples;

import cn.ac.ios.justin.annotations.Examples;
import cn.ac.ios.justin.annotations.InRange;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.Map;

/** Annotated contracts in the JSR-380 style plus Justin's own annotations. */
public class AccountService {

    private final Map<String, Long> balances = new HashMap<>();

    public void open(@NotBlank @Size(min = 3, max = 16) String account, @Min(0) long initial) {
        if (account == null || account.trim().isEmpty()) throw new IllegalArgumentException("blank account");
        if (account.length() < 3 || account.length() > 16) throw new IllegalArgumentException("bad length");
        if (initial < 0) throw new IllegalArgumentException("negative");
        if (balances.containsKey(account)) throw new IllegalStateException("exists");
        balances.put(account, initial);
    }

    public long balance(@NotNull String account) {
        Long b = balances.get(account);
        if (b == null) throw new IllegalArgumentException("unknown");
        return b;
    }

    public long withdraw(@NotNull String account, @Min(1) @Max(1_000_000) long amount) {
        long b = balance(account);
        if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("amount");
        if (b < amount) throw new IllegalStateException("insufficient");
        balances.put(account, b - amount);
        return b - amount;
    }

    public static int tier(@InRange(min = 0, max = 100) int score) {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score");
        if (score >= 90) return 3;
        if (score >= 60) return 2;
        if (score >= 30) return 1;
        return 0;
    }

    public static boolean validEmail(@Email @Examples(value = {"\"a@b.com\"", "\"x.y@host.org\""}, invalid = {"\"no-at\"", "\"\""}) String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[a-z]+$");
    }
}
