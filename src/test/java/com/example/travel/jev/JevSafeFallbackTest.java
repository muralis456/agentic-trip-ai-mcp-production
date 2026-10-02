package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that JEV is an optional decision accelerator and never becomes a
 * hard dependency for the core agent workflow.
 */
class JevSafeFallbackTest {

    @Test
    void missingApiKeyMustNotPreventJevClientConstruction() {
        RestClient.Builder builder = mock(RestClient.Builder.class);

        assertDoesNotThrow(() ->
                new JevDecisionClient(builder, "https://api.typesafe.ai", "", "jev-latest"));

        JevDecisionClient client =
                new JevDecisionClient(builder, "https://api.typesafe.ai", "", "jev-latest");

        assertThrows(
                JevDecisionClient.JevUnavailableException.class,
                () -> client.choose(
                        Map.of("request", "plan a trip"),
                        "Choose a route",
                        Map.of("REPLAN", "recover", "HITL", "ask user")));
        verifyNoInteractions(builder);
    }

    @Test
    void goalDecisionFallsBackToDeterministicReplanWhenJevIsUnavailable() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        when(decisions.choose(any(), anyString(), anyMap()))
                .thenThrow(new JevDecisionClient.JevUnavailableException("no credits"));

        JevGoalDecisionService service = new JevGoalDecisionService(decisions);
        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setStatus(GoalEvaluation.Status.PARTIAL);
        evaluation.setRecoverable(true);
        evaluation.setReason("flight provider unavailable");
        evaluation.setUnmetCriteria(List.of("flight"));

        JevGoalDecisionService.Decision result =
                service.decide(new TravelState(Map.of(TravelState.USER_REQUEST, "Japan trip")), evaluation);

        assertEquals("REPLAN", result.route());
        assertFalse(result.accepted());
        assertEquals(0.0, result.confidence());
        assertTrue(result.reason().contains("jev unavailable"));
    }

    @Test
    void goalDecisionFallsBackToHitlForNonRecoverableFailure() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        when(decisions.choose(any(), anyString(), anyMap()))
                .thenThrow(new JevDecisionClient.JevUnavailableException("no credits"));

        JevGoalDecisionService service = new JevGoalDecisionService(decisions);
        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setStatus(GoalEvaluation.Status.FAILED);
        evaluation.setRecoverable(false);

        JevGoalDecisionService.Decision result =
                service.decide(new TravelState(Map.of()), evaluation);

        assertEquals("HITL", result.route());
        assertFalse(result.accepted());
    }

    @Test
    void replanDecisionFallsBackToUnmetCapability() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        when(decisions.choose(any(), anyString(), anyMap()))
                .thenThrow(new JevDecisionClient.JevUnavailableException("no credits"));

        JevReplanDecisionService service = new JevReplanDecisionService(decisions);
        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setUnmetCriteria(List.of("hotel budget exceeded"));

        JevReplanDecisionService.Decision result =
                service.choose(new TravelState(Map.of(TravelState.USER_REQUEST, "Tokyo trip")),
                        evaluation,
                        List.of("reduce_hotel_budget"));

        assertEquals("HOTEL", result.action());
        assertFalse(result.accepted());
    }

    @Test
    void modelRoutingFallsBackToExistingJavaPolicy() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        when(decisions.choose(any(), anyString(), anyMap()))
                .thenThrow(new JevDecisionClient.JevUnavailableException("no credits"));

        JevModelRoutingService service = new JevModelRoutingService(decisions);
        TravelState state = new TravelState(Map.of(
                TravelState.MODEL_POLICY, "FAST",
                TravelState.USER_REQUEST, "simple weather question"));

        assertEquals("FAST", service.choose(state));
    }

    @Test
    void hitlDecisionMustNeverAutoCompleteWhenGoalIsNotAchieved() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        JevHitlDecisionService service = new JevHitlDecisionService(decisions);

        GoalEvaluation evaluation = new GoalEvaluation();
        evaluation.setStatus(GoalEvaluation.Status.PARTIAL);

        TravelState state = new TravelState(Map.of(
                TravelState.GOAL_EVALUATION, evaluation,
                TravelState.VALIDATION_ERRORS, List.of(),
                TravelState.USER_INPUT_REQUIRED, false));

        JevHitlDecisionService.Decision result = service.decide(state);

        assertEquals("ASK_USER", result.route());
        assertTrue(result.accepted());
        verifyNoInteractions(decisions);
    }

    @Test
    void ragRoutingFallsBackToRagWhenKnowledgeIsRequired() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        when(decisions.choose(any(), anyString(), anyMap()))
                .thenThrow(new JevDecisionClient.JevUnavailableException("no credits"));

        JevRagDecisionService service = new JevRagDecisionService(decisions);
        TravelState state = new TravelState(Map.of(
                TravelState.USER_REQUEST, "Tell me about Tokyo",
                TravelState.DESTINATION, "Tokyo",
                TravelState.NEEDS_KNOWLEDGE, true));

        JevRagDecisionService.Decision result = service.decide(state);

        assertEquals("RAG", result.route());
        assertFalse(result.accepted());
    }

    @Test
    void providerRoutingMustLeaveProviderUnsetWhenJevIsUnavailable() {
        JevDecisionService decisions = mock(JevDecisionService.class);
        when(decisions.choose(any(), anyString(), anyMap()))
                .thenThrow(new JevDecisionClient.JevUnavailableException("no credits"));

        JevProviderDecisionService service = new JevProviderDecisionService(decisions);

        JevProviderDecisionService.Decision result =
                service.choose(new TravelState(Map.of(TravelState.USER_REQUEST, "BLR to NRT")),
                        "provider unavailable");

        assertEquals("", result.provider());
        assertFalse(result.accepted());
    }
}
