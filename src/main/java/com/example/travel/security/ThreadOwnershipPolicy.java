package com.example.travel.security;

import java.util.UUID;

/**
 * Validates ownership of server-generated LangGraph thread identifiers.
 * Thread IDs are generated as <authenticated-user-id>-<UUID>; a mere prefix
 * match is insufficient because users such as "alice" and "alice-ops" can
 * otherwise overlap.
 */
public final class ThreadOwnershipPolicy {
    private ThreadOwnershipPolicy() {}

    public static String requireOwnedThread(String authenticatedUserId, String threadId) {
        String userId = authenticatedUserId == null ? "" : authenticatedUserId.trim();
        String key = threadId == null ? "" : threadId.trim();
        if (userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Thread id is required");
        }
        String prefix = userId + "-";
        if (!key.startsWith(prefix)) {
            throw new UnauthorizedThreadAccessException();
        }
        String suffix = key.substring(prefix.length());
        try {
            UUID.fromString(suffix);
        } catch (IllegalArgumentException invalidId) {
            throw new UnauthorizedThreadAccessException();
        }
        return key;
    }

    public static final class UnauthorizedThreadAccessException extends RuntimeException {
        public UnauthorizedThreadAccessException() {
            super("Travel plan not found");
        }
    }
}
