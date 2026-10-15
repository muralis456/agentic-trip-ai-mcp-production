package com.example.travel.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceSecurityTest {
    @Mock
    private AgentIdempotencyRepository repository;

    @InjectMocks
    private IdempotencyService service;

    @Test
    void claimsKeyAtomicallyForFirstRequest() {
        when(repository.insertIfAbsent(eq("alice"), eq("request-1"), eq("hash-a"), any(OffsetDateTime.class)))
                .thenReturn(1);

        assertTrue(service.claim("alice", "request-1", "hash-a"));
        verify(repository, never()).findById(any());
    }

    @Test
    void rejectsConcurrentDuplicateForSameUserAndPayload() {
        when(repository.insertIfAbsent(eq("alice"), eq("request-1"), eq("hash-a"), any(OffsetDateTime.class)))
                .thenReturn(0);
        when(repository.findById(new AgentIdempotencyId("alice", "request-1")))
                .thenReturn(Optional.of(new AgentIdempotency(
                        new AgentIdempotencyId("alice", "request-1"), "hash-a", "IN_PROGRESS", null,
                        OffsetDateTime.now(ZoneOffset.UTC))));

        assertThrows(IdempotencyService.DuplicateRequestException.class,
                () -> service.claim("alice", "request-1", "hash-a"));
    }

    @Test
    void rejectsReusingKeyForDifferentPayload() {
        when(repository.insertIfAbsent(eq("alice"), eq("request-1"), eq("hash-new"), any(OffsetDateTime.class)))
                .thenReturn(0);
        when(repository.findById(new AgentIdempotencyId("alice", "request-1")))
                .thenReturn(Optional.of(new AgentIdempotency(
                        new AgentIdempotencyId("alice", "request-1"), "hash-old", "COMPLETED", "{\"ok\":true}",
                        OffsetDateTime.now(ZoneOffset.UTC))));

        assertThrows(IdempotencyService.KeyReuseException.class,
                () -> service.claim("alice", "request-1", "hash-new"));
    }

    @Test
    void scopesSameKeyIndependentlyPerUser() {
        when(repository.insertIfAbsent(eq("alice"), eq("same-key"), eq("hash-a"), any(OffsetDateTime.class)))
                .thenReturn(1);
        when(repository.insertIfAbsent(eq("bob"), eq("same-key"), eq("hash-b"), any(OffsetDateTime.class)))
                .thenReturn(1);

        assertTrue(service.claim("alice", "same-key", "hash-a"));
        assertTrue(service.claim("bob", "same-key", "hash-b"));
    }

    @Test
    void doesNotReturnAnotherUsersCachedResponse() {
        when(repository.findById(new AgentIdempotencyId("alice", "shared-key")))
                .thenReturn(Optional.empty());

        assertTrue(service.find("alice", "shared-key", "hash").isEmpty());
    }
}
