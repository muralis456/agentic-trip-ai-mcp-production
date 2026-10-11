package com.example.travel.controller;

import com.example.travel.observability.AgentEvaluationLedger;
import com.example.travel.observability.AgentModelUsageLedger;
import com.example.travel.observability.AgentRuntimeEventLedger;
import com.example.travel.observability.ModelPricingProperties;
import com.example.travel.repository.AgentModelUsageAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationDashboardControllerTest {

    private EvaluationDashboardController controller(AgentEvaluationLedger evaluations,
            AgentModelUsageLedger usage, AgentRuntimeEventLedger runtime, ModelPricingProperties pricing) {
        when(evaluations.search(anyString(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(evaluations.summary(anyString(), anyInt())).thenReturn(Map.of(
                "evaluations", 0L, "achieved", 0L, "partial", 0L, "failed", 0L,
                "needsUser", 0L, "averageScore", 0.0));
        when(usage.aggregateForUser(anyString(), anyInt())).thenReturn(List.of());
        when(usage.aggregateTripsForUser(anyString(), anyInt())).thenReturn(List.of());
        when(evaluations.labelsForThreads(anyString(), anyCollection())).thenReturn(Map.of());
        when(runtime.aggregateForUser(anyString(), anyInt())).thenReturn(List.of());
        when(runtime.recentForUser(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        return new EvaluationDashboardController(evaluations, usage, runtime, new SimpleMeterRegistry(), pricing);
    }

    @Test
    void dashboardScopesAllDurableSourcesToAuthenticatedPrincipal() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        AgentRuntimeEventLedger runtime = mock(AgentRuntimeEventLedger.class);
        ModelPricingProperties pricing = new ModelPricingProperties();
        var controller = controller(evaluations, usage, runtime, pricing);
        Authentication authentication = new UsernamePasswordAuthenticationToken("alice", "unused");

        Map<String, Object> response = controller.dashboard(50, 30, 0, 50, null, null, authentication);

        verify(evaluations).search("alice", 30, 0, 50, null, null, "createdAt", "DESC");
        verify(evaluations).summary("alice", 30);
        verify(usage).aggregateForUser("alice", 30);
        verify(usage).aggregateTripsForUser("alice", 30);
        verify(runtime).aggregateForUser("alice", 30);
        assertTrue(response.containsKey("summary"));
        assertTrue(response.containsKey("traces"));
        assertTrue(response.containsKey("tracePagination"));
        assertTrue(response.containsKey("modelUsage"));
        assertTrue(response.containsKey("runtimeEvents"));
    }

    @Test
    void missingAuthenticationDoesNotQueryAnyOtherUsersHistory() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        AgentRuntimeEventLedger runtime = mock(AgentRuntimeEventLedger.class);
        var controller = controller(evaluations, usage, runtime, new ModelPricingProperties());

        controller.dashboard(50, 30, 0, 50, null, null, null);

        verify(evaluations).search("", 30, 0, 50, null, null, "createdAt", "DESC");
        verify(usage).aggregateForUser("", 30);
        verify(runtime).aggregateForUser("", 30);
        verify(evaluations, never()).search(eq("alice"), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
        verify(usage, never()).aggregateForUser(eq("alice"), anyInt());
        verify(runtime, never()).aggregateForUser(eq("alice"), anyInt());
    }

    @Test
    void requestedWindowAndPageSizeAreBounded() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        AgentRuntimeEventLedger runtime = mock(AgentRuntimeEventLedger.class);
        var controller = controller(evaluations, usage, runtime, new ModelPricingProperties());
        Authentication authentication = new UsernamePasswordAuthenticationToken("alice", "unused");

        Map<String, Object> response = controller.dashboard(5000, 99999, -4, 1000, null, null, authentication);

        verify(evaluations).search("alice", 3650, 0, 100, null, null);
        verify(usage).aggregateForUser("alice", 3650);
        assertEquals(3650, response.get("modelUsageWindowDays"));
    }

    @Test
    void dashboardReportsErrorRateAndAverageLatencyFromDatabaseAggregates() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        AgentRuntimeEventLedger runtime = mock(AgentRuntimeEventLedger.class);
        var controller = controller(evaluations, usage, runtime, new ModelPricingProperties());

        AgentModelUsageAggregate aggregate = mock(AgentModelUsageAggregate.class);
        when(aggregate.getModel()).thenReturn("test-model");
        when(aggregate.getCalls()).thenReturn(4L);
        when(aggregate.getErrors()).thenReturn(1L);
        when(aggregate.getInputTokens()).thenReturn(100L);
        when(aggregate.getOutputTokens()).thenReturn(60L);
        when(aggregate.getDurationMs()).thenReturn(800L);
        when(usage.aggregateForUser("alice", 30)).thenReturn(List.of(aggregate));

        Map<String, Object> response = controller.dashboard(50, 30, 0, 50, null, null,
                new UsernamePasswordAuthenticationToken("alice", "unused"));
        Map<?, ?> summary = (Map<?, ?>) response.get("summary");

        assertEquals(4L, summary.get("llmCalls"));
        assertEquals(1L, summary.get("llmErrors"));
        assertEquals(0.25, (Double) summary.get("llmErrorRate"), 0.0001);
        assertEquals(200L, summary.get("averageLlmDurationMs"));
        assertEquals(100L, summary.get("inputTokens"));
        assertEquals(60L, summary.get("outputTokens"));
    }
}
