package com.example.travel.controller;

import com.example.travel.observability.AgentEvaluationLedger;
import com.example.travel.observability.AgentModelUsageLedger;
import com.example.travel.observability.ModelPricingProperties;
import com.example.travel.repository.AgentModelUsageAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationDashboardControllerTest {

    @Test
    void dashboardScopesBothDurableSourcesToAuthenticatedPrincipal() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        ModelPricingProperties pricing = mock(ModelPricingProperties.class);
        when(evaluations.recent(eq("alice"), anyInt())).thenReturn(List.of());
        when(evaluations.summary("alice")).thenReturn(Map.of("evaluations", 0L));
        when(usage.aggregateForUser("alice", 30)).thenReturn(List.of());

        EvaluationDashboardController controller =
                new EvaluationDashboardController(evaluations, usage, new SimpleMeterRegistry(), pricing);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("alice", "unused");

        Map<String, Object> response = controller.dashboard(50, 30, authentication);

        verify(evaluations).recent("alice", 50);
        verify(evaluations).summary("alice");
        verify(usage).aggregateForUser("alice", 30);
        assertTrue(response.containsKey("summary"));
        assertTrue(response.containsKey("traces"));
        assertTrue(response.containsKey("modelUsage"));
    }

    @Test
    void missingAuthenticationDoesNotQueryAnyUsersHistory() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        ModelPricingProperties pricing = mock(ModelPricingProperties.class);
        when(evaluations.recent("", 50)).thenReturn(List.of());
        when(evaluations.summary("")).thenReturn(Map.of("evaluations", 0L));
        when(usage.aggregateForUser("", 30)).thenReturn(List.of());

        EvaluationDashboardController controller =
                new EvaluationDashboardController(evaluations, usage, new SimpleMeterRegistry(), pricing);

        controller.dashboard(50, 30, null);

        verify(evaluations).recent("", 50);
        verify(evaluations).summary("");
        verify(usage).aggregateForUser("", 30);
        verify(evaluations, never()).recent(eq("alice"), anyInt());
        verify(usage, never()).aggregateForUser(eq("alice"), anyInt());
    }

    @Test
    void requestedWindowIsBoundedBeforeQueryingTelemetry() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        ModelPricingProperties pricing = mock(ModelPricingProperties.class);
        when(evaluations.recent(eq("alice"), anyInt())).thenReturn(List.of());
        when(evaluations.summary("alice")).thenReturn(Map.of("evaluations", 0L));
        when(usage.aggregateForUser("alice", 365)).thenReturn(List.of());

        EvaluationDashboardController controller =
                new EvaluationDashboardController(evaluations, usage, new SimpleMeterRegistry(), pricing);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("alice", "unused");

        Map<String, Object> response = controller.dashboard(50, 9999, authentication);

        verify(usage).aggregateForUser("alice", 365);
        assertEquals(365, response.get("modelUsageWindowDays"));
    }

    @Test
    void dashboardReportsErrorRateAndAverageLatencyFromDatabaseAggregates() {
        AgentEvaluationLedger evaluations = mock(AgentEvaluationLedger.class);
        AgentModelUsageLedger usage = mock(AgentModelUsageLedger.class);
        ModelPricingProperties pricing = mock(ModelPricingProperties.class);
        when(evaluations.recent(eq("alice"), anyInt())).thenReturn(List.of());
        when(evaluations.summary("alice")).thenReturn(Map.of("evaluations", 0L));

        AgentModelUsageAggregate aggregate = mock(AgentModelUsageAggregate.class);
        when(aggregate.getModel()).thenReturn("test-model");
        when(aggregate.getCalls()).thenReturn(4L);
        when(aggregate.getErrors()).thenReturn(1L);
        when(aggregate.getInputTokens()).thenReturn(100L);
        when(aggregate.getOutputTokens()).thenReturn(60L);
        when(aggregate.getDurationMs()).thenReturn(800L);
        when(usage.aggregateForUser("alice", 30)).thenReturn(List.of(aggregate));

        EvaluationDashboardController controller =
                new EvaluationDashboardController(evaluations, usage, new SimpleMeterRegistry(), pricing);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken("alice", "unused");

        Map<String, Object> response = controller.dashboard(50, 30, authentication);
        Map<?, ?> summary = (Map<?, ?>) response.get("summary");

        assertEquals(4L, summary.get("llmCalls"));
        assertEquals(1L, summary.get("llmErrors"));
        assertEquals(0.25, (Double) summary.get("llmErrorRate"), 0.0001);
        assertEquals(200L, summary.get("averageLlmDurationMs"));
        assertEquals(100L, summary.get("inputTokens"));
        assertEquals(60L, summary.get("outputTokens"));
    }
}
