package com.example.travel.mcp;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-invocation correlation context used to join AgenticTripAI and MCP logs.
 */
public final class McpCorrelationContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private McpCorrelationContext() {
    }

    public static Scope open(String correlationId) {
        String previous = CURRENT.get();
        CURRENT.set(correlationId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String currentOrCreate() {
        return CURRENT.get() != null ? CURRENT.get() : UUID.randomUUID().toString();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
