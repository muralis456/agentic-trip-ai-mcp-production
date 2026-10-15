package com.example.travel.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ThreadOwnershipPolicyTest {
    private String thread(String user) {
        return user + "-" + UUID.randomUUID();
    }

    @Test
    void allowsOnlyTheAuthenticatedUsersServerGeneratedThreadId() {
        String id = thread("alice");
        assertEquals(id, ThreadOwnershipPolicy.requireOwnedThread("alice", id));
    }

    @Test
    void acceptsCanonicalUuidCaseInsensitively() {
        String id = "alice-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
        assertEquals(id, ThreadOwnershipPolicy.requireOwnedThread("alice", id));
    }

    @Test
    void deniesAnotherUsersThread() {
        assertThrows(ThreadOwnershipPolicy.UnauthorizedThreadAccessException.class,
                () -> ThreadOwnershipPolicy.requireOwnedThread("alice", thread("bob")));
    }

    @Test
    void deniesOverlappingUserIdPrefixAttack() {
        // "alice-ops-<uuid>" must not pass an "alice-" prefix check.
        String opsThread = thread("alice-ops");
        assertThrows(ThreadOwnershipPolicy.UnauthorizedThreadAccessException.class,
                () -> ThreadOwnershipPolicy.requireOwnedThread("alice", opsThread));
    }

    @Test
    void deniesForgedThreadSuffixInsteadOfAcceptingAnyPrefixMatch() {
        assertThrows(ThreadOwnershipPolicy.UnauthorizedThreadAccessException.class,
                () -> ThreadOwnershipPolicy.requireOwnedThread("alice", "alice-admin-secret"));
    }

    @Test
    void rejectsNonCanonicalUuidThatJavaParserWouldOtherwiseAccept() {
        assertThrows(ThreadOwnershipPolicy.UnauthorizedThreadAccessException.class,
                () -> ThreadOwnershipPolicy.requireOwnedThread("alice", "alice-1-1-1-1-1"));
    }

    @Test
    void rejectsMissingIdentityAndThreadId() {
        assertThrows(IllegalArgumentException.class,
                () -> ThreadOwnershipPolicy.requireOwnedThread(" ", thread("alice")));
        assertThrows(IllegalArgumentException.class,
                () -> ThreadOwnershipPolicy.requireOwnedThread("alice", " "));
    }
}
