package cn.ac.ios.justin.exec;

/**
 * Defined inside the sandbox class loader (from its own bytes), so subject classes can call it.
 * The interceptor rewrites forbidden JDK calls into blocked(...) which throws a SecurityException
 * that the Invoker recognizes by its message prefix.
 */
public final class SandboxSupport {
    public static final String PREFIX = "[justin-blocked] ";

    private SandboxSupport() {}

    /**
     * Number of blocked calls so far and the latest one. The subject may catch the SecurityException and carry on
     * along a fallback path (jline does when it cannot start infocmp); what it returns then is a product of the
     * sandbox, not of the subject, so the Invoker compares this counter before and after an execution.
     */
    public static volatile int blockedCount;
    public static volatile String lastBlocked;

    public static void blocked(String what) {
        blockedCount++;
        lastBlocked = what;
        throw new SecurityException(PREFIX + what);
    }
}
