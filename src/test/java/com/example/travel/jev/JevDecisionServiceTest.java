package com.example.travel.jev;

import com.example.travel.observability.AgentObservabilityService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JevDecisionServiceTest {

    @Test
    void choiceDecisionPreservesThePolicyRejectionReason() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        JevDecisionPolicy policy = policy();
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.choose(Map.of("request", "travel"), "choose route", Map.of("RAG", "internal", "WEB", "live")))
                .thenReturn(new JevDecisionClient.JevChoiceDecision(
                        "nimble:latest",
                        "WEB",
                        0.56,
                        Map.of("WEB", 0.67, "RAG", 0.18, "BOTH", 0.12, "NONE", 0.03)));

        JevDecisionService service = new JevDecisionService(client, policy, observability);
        JevDecisionService.Decision decision = service.choose(
                JevDecisionPolicy.DecisionKind.RAG,
                Map.of("request", "travel"),
                "choose route",
                Map.of("RAG", "internal", "WEB", "live"));

        assertFalse(decision.accepted());
        assertEquals("selected_probability_below_threshold", decision.acceptanceReason());
    }

    @Test
    void yesNoDecisionExplainsThresholdRejection() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.yesNo(Map.of(), "decide", "yes", "no"))
                .thenReturn(new JevDecisionClient.JevNoulDecision(0.60));

        JevDecisionService service = new JevDecisionService(client, policy(), observability);
        JevDecisionService.YesNoDecision decision = service.yesNo(Map.of(), "decide", "yes", "no");

        assertFalse(decision.accepted());
        assertEquals("probability_below_threshold", decision.reason());
    }

    @Test
    void scoreDecisionExplainsThresholdRejection() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.score(Map.of(), "score", java.util.List.of("criterion")))
                .thenReturn(new JevDecisionClient.JevScoreDecision(0.8, 0.60));

        JevDecisionService service = new JevDecisionService(client, policy(), observability);
        JevDecisionService.ScoreDecision decision =
                service.score(Map.of(), "score", java.util.List.of("criterion"));

        assertFalse(decision.accepted());
        assertEquals("confidence_below_threshold", decision.reason());
    }

    @Test
    void rejectsInvalidYesNoProbabilityEvenIfAboveThreshold() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.yesNo(Map.of(), "decide", "yes", "no"))
                .thenReturn(new JevDecisionClient.JevNoulDecision(1.1));

        JevDecisionService service = new JevDecisionService(client, policy(), observability);
        JevDecisionService.YesNoDecision decision = service.yesNo(Map.of(), "decide", "yes", "no");

        assertFalse(decision.accepted());
        assertEquals("invalid_probability", decision.reason());
    }

    @Test
    void rejectsInvalidScoreEvenWhenConfidencePassesThreshold() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.score(Map.of(), "score", java.util.List.of("criterion")))
                .thenReturn(new JevDecisionClient.JevScoreDecision(Double.NaN, 0.99));

        JevDecisionService service = new JevDecisionService(client, policy(), observability);
        JevDecisionService.ScoreDecision decision =
                service.score(Map.of(), "score", java.util.List.of("criterion"));

        assertFalse(decision.accepted());
        assertEquals("invalid_score", decision.reason());
    }

    @Test
    void nullDecisionKindIsRejectedWithoutCallingProvider() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        JevDecisionService service = new JevDecisionService(client, policy(), observability);

        JevDecisionService.Decision decision =
                service.choose(null, Map.of(), "choose route", Map.of("RAG", "internal"));

        assertFalse(decision.accepted());
        assertEquals("missing_decision_kind", decision.acceptanceReason());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void strongModelRoutingDecisionIsAccepted() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.choose(Map.of(), "choose", Map.of("FAST", "simple", "BALANCED", "normal")))
                .thenReturn(new JevDecisionClient.JevChoiceDecision(
                        "nimble:latest", "BALANCED", 0.82,
                        Map.of("BALANCED", 0.92, "FAST", 0.06, "REASONING", 0.02)));

        JevDecisionService service = new JevDecisionService(client, policy(), observability);
        JevDecisionService.Decision decision = service.choose(
                JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                Map.of(),
                "choose",
                Map.of("FAST", "simple", "BALANCED", "normal"));

        assertTrue(decision.accepted());
        assertEquals("accepted", decision.acceptanceReason());
    }

    @Test
    void rejectsHighConfidenceChoiceNotDeclaredByDecisionCriteria() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        when(client.choose(Map.of(), "choose", Map.of("FAST", "simple", "BALANCED", "normal")))
                .thenReturn(new JevDecisionClient.JevChoiceDecision(
                        "nimble:latest", "TOOL", 0.99,
                        Map.of("TOOL", 0.99, "FAST", 0.01)));

        JevDecisionService service = new JevDecisionService(client, policy(), observability);
        JevDecisionService.Decision decision = service.choose(
                JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                Map.of(),
                "choose",
                Map.of("FAST", "simple", "BALANCED", "normal"));

        assertFalse(decision.accepted());
        assertEquals("choice_not_allowed", decision.acceptanceReason());
    }

    @Test
    void invalidCriteriaAreRejectedWithoutCallingProvider() {
        JevDecisionClient client = mock(JevDecisionClient.class);
        AgentObservabilityService observability = mock(AgentObservabilityService.class);
        JevDecisionService service = new JevDecisionService(client, policy(), observability);

        JevDecisionService.Decision decision = service.choose(
                JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                Map.of(),
                "choose",
                Map.of());

        assertFalse(decision.accepted());
        assertEquals("invalid_criteria", decision.acceptanceReason());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private JevDecisionPolicy policy() {
        return new JevDecisionPolicy(
                0.0, 0.75, 0.15,
                0.0, 0.60, 0.15,
                0.0, 0.70, 0.15,
                0.50, 0.75, 0.10,
                0.80, 0.80, 0.20,
                0.50, 0.75, 0.15,
                0.75, 0.90, 0.75,
                0.75, 0.75);
    }
}
