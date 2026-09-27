package com.example.travel.tool;

/**
 * Request-scoped identity and approval context propagated to worker threads
 * before MCP tool execution.
 *
 * <p>ThreadLocal is intentional here: ProductionExecutionNode executes
 * specialist tasks in parallel, so identity must not be stored in a shared
 * mutable field.</p>
 */
public final class ToolInvocationContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    public static Scope open(String userId, String role) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(userId, role, false));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Scope open(String userId, String role, boolean approvalGranted) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(userId, role, approvalGranted));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static java.util.Optional<Context> current() {
        return java.util.Optional.ofNullable(CURRENT.get());
    }

    public record Context(String userId, String role, boolean approvalGranted) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
