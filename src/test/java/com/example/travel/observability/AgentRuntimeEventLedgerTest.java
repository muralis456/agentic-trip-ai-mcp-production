package com.example.travel.observability;

import com.example.travel.repository.AgentRuntimeEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeEventLedgerTest {
    @Test
    void unboundedWindowUsesQueriesWithoutNullableTimestampParameters() {
        AgentRuntimeEventRepository repository = mock(AgentRuntimeEventRepository.class);
        AgentRuntimeEventLedger ledger = new AgentRuntimeEventLedger(repository, new ModelPricingProperties());
        when(repository.aggregateForUser("alice")).thenReturn(List.of());
        when(repository.providerCostsByThread("alice")).thenReturn(List.of());
        when(repository.recentForUser(eq("alice"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertDoesNotThrow(() -> ledger.aggregateForUser("alice", 0));
        assertDoesNotThrow(() -> ledger.providerCostsByThread("alice", 0));
        assertDoesNotThrow(() -> ledger.recentForUser("alice", 0, 25));

        verify(repository).aggregateForUser("alice");
        verify(repository).providerCostsByThread("alice");
        verify(repository).recentForUser(eq("alice"), any(Pageable.class));
        verify(repository, never()).aggregateForUserSince(eq("alice"), any(Instant.class));
        verify(repository, never()).providerCostsByThreadSince(eq("alice"), any(Instant.class));
        verify(repository, never()).recentForUserSince(eq("alice"), any(Instant.class), any(Pageable.class));
    }

    @Test
    void boundedWindowUsesExplicitlyTypedTimestampQueries() {
        AgentRuntimeEventRepository repository = mock(AgentRuntimeEventRepository.class);
        AgentRuntimeEventLedger ledger = new AgentRuntimeEventLedger(repository, new ModelPricingProperties());
        when(repository.aggregateForUserSince(eq("alice"), any(Instant.class))).thenReturn(List.of());
        when(repository.providerCostsByThreadSince(eq("alice"), any(Instant.class))).thenReturn(List.of());
        when(repository.recentForUserSince(eq("alice"), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertDoesNotThrow(() -> ledger.aggregateForUser("alice", 30));
        assertDoesNotThrow(() -> ledger.providerCostsByThread("alice", 30));
        assertDoesNotThrow(() -> ledger.recentForUser("alice", 30, 25));

        verify(repository).aggregateForUserSince(eq("alice"), any(Instant.class));
        verify(repository).providerCostsByThreadSince(eq("alice"), any(Instant.class));
        verify(repository).recentForUserSince(eq("alice"), any(Instant.class), any(Pageable.class));
        verify(repository, never()).aggregateForUser("alice");
        verify(repository, never()).providerCostsByThread("alice");
        verify(repository, never()).recentForUser(eq("alice"), any(Pageable.class));
    }
}
