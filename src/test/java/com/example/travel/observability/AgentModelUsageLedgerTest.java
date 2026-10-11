package com.example.travel.observability;

import com.example.travel.entity.AgentModelUsageEvent;
import com.example.travel.repository.AgentModelUsageEventRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentModelUsageLedgerTest {
    @Test
    void persistsUsageWithUserThreadAndSafeNonNegativeMetrics() {
        AgentModelUsageEventRepository repository = mock(AgentModelUsageEventRepository.class);
        AgentModelUsageLedger ledger = new AgentModelUsageLedger(repository);

        ledger.record("alice", "thread-123", "llama3.2:3b", "PLANNER",
                true, 120, 45, 230);

        var captor = org.mockito.ArgumentCaptor.forClass(AgentModelUsageEvent.class);
        verify(repository).save(captor.capture());
        AgentModelUsageEvent event = captor.getValue();
        assertEquals("alice", event.getUserId());
        assertEquals("thread-123", event.getThreadId());
        assertEquals("llama3.2:3b", event.getModel());
        assertEquals("PLANNER", event.getOperation());
        assertEquals("SUCCESS", event.getStatus());
        assertEquals(120, event.getInputTokens());
        assertEquals(45, event.getOutputTokens());
        assertEquals(230, event.getDurationMs());
        assertNotNull(event.getCreatedAt());
    }

    @Test
    void doesNotPersistWhenUserContextIsMissing() {
        AgentModelUsageEventRepository repository = mock(AgentModelUsageEventRepository.class);
        AgentModelUsageLedger ledger = new AgentModelUsageLedger(repository);

        ledger.record("", "thread-123", "model", "PLANNER", true, 1, 1, 1);

        verify(repository, never()).save(any());
    }

    @Test
    void telemetryPersistenceFailureDoesNotEscapeIntoTravelExecution() {
        AgentModelUsageEventRepository repository = mock(AgentModelUsageEventRepository.class);
        when(repository.save(any(AgentModelUsageEvent.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        AgentModelUsageLedger ledger = new AgentModelUsageLedger(repository);

        assertDoesNotThrow(() -> ledger.record("alice", "thread-123", "model",
                "PLANNER", false, 0, 0, 25));
    }
}
