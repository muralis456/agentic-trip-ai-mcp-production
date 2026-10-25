package com.example.travel.jev;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JevDecisionPolicyTest {

    @Test
    void goalReplanUsesReplanThresholdsAndIsAcceptedWhenStrongEnough() {
        JevDecisionPolicy policy = policy();

        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("REPLAN", 0.78);
        probabilities.put("HITL", 0.22);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "REPLAN", 0.86, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.GOAL, decision);

        assertTrue(result.accepted());
        assertEquals("accepted", result.reason());
        assertEquals(0.78, result.selectedProbability(), 0.0001);
        assertEquals(0.56, result.margin(), 0.0001);
        assertTrue(policy.accepts(JevDecisionPolicy.DecisionKind.GOAL, decision));
    }

    @Test
    void goalHitlKeepsStrictSafetyThreshold() {
        JevDecisionPolicy policy = policy();

        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("HITL", 0.91);
        probabilities.put("REPLAN", 0.09);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "HITL", 0.70, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.GOAL, decision);

        assertFalse(result.accepted());
        assertEquals("confidence_below_threshold", result.reason());
    }

    @Test
    void strongModelRoutingDistributionIsNotRejectedForLowSelfReportedConfidence() {
        JevDecisionPolicy policy = policy();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("BALANCED", 0.67);
        probabilities.put("FAST", 0.20);
        probabilities.put("REASONING", 0.13);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "BALANCED", 0.29, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.MODEL_ROUTING, decision);

        assertTrue(result.accepted());
        assertEquals("accepted", result.reason());
        assertEquals(0.67, result.selectedProbability(), 0.0001);
        assertEquals(0.47, result.margin(), 0.0001);
    }

    @Test
    void strongRagDistributionIsNotRejectedForLowSelfReportedConfidence() {
        JevDecisionPolicy policy = policy();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("WEB", 0.77);
        probabilities.put("RAG", 0.12);
        probabilities.put("BOTH", 0.08);
        probabilities.put("NONE", 0.03);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "WEB", 0.45, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.RAG, decision);

        assertTrue(result.accepted());
        assertEquals("accepted", result.reason());
        assertEquals(0.77, result.selectedProbability(), 0.0001);
        assertEquals(0.65, result.margin(), 0.0001);
    }

    @Test
    void weakProviderRecommendationStillFailsProbabilityGate() {
        JevDecisionPolicy policy = policy();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("IGNAV", 0.55);
        probabilities.put("AVIATIONSTACK", 0.45);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "IGNAV", 0.01, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.PROVIDER, decision);

        assertFalse(result.accepted());
        assertEquals("selected_probability_below_threshold", result.reason());
    }

    @Test
    void rejectedDecisionExplainsWhichGateFailed() {
        JevDecisionPolicy policy = policy();

        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("REPLAN", 0.70);
        probabilities.put("HITL", 0.30);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "REPLAN", 0.90, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.GOAL, decision);

        assertFalse(result.accepted());
        assertEquals("selected_probability_below_threshold", result.reason());
    }

    @Test
    void choiceAndProbabilityLabelsAreMatchedCaseInsensitively() {
        JevDecisionPolicy policy = policy();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("REPLAN", 0.78);
        probabilities.put("HITL", 0.22);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "replan", 0.86, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.GOAL, decision);

        assertTrue(result.accepted());
        assertEquals(0.78, result.selectedProbability(), 0.0001);
        assertEquals(0.56, result.margin(), 0.0001);
    }

    @Test
    void rejectsProbabilityDistributionWithNanOrOutOfRangeValues() {
        JevDecisionPolicy policy = policy();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("REPLAN", Double.NaN);
        probabilities.put("HITL", 0.22);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "REPLAN", 0.86, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.GOAL, decision);

        assertFalse(result.accepted());
        assertEquals("invalid_probability_distribution", result.reason());
    }

    @Test
    void rejectsChoiceMissingFromProbabilityDistribution() {
        JevDecisionPolicy policy = policy();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("HITL", 0.91);
        probabilities.put("ACHIEVED", 0.09);

        var decision = new JevDecisionClient.JevChoiceDecision(
                "tev1:4b", "REPLAN", 0.86, probabilities);

        var result = policy.acceptanceReason(JevDecisionPolicy.DecisionKind.GOAL, decision);

        assertFalse(result.accepted());
        assertEquals("choice_missing_from_probabilities", result.reason());
    }

    private JevDecisionPolicy policy() {
        return new JevDecisionPolicy(
                0.0, 0.75, 0.15,
                0.0, 0.60, 0.15,
                0.0, 0.70, 0.15,
                0.0, 0.75, 0.10,
                0.80, 0.80, 0.20,
                0.50, 0.75, 0.15,
                0.75, 0.90, 0.75,
                0.75, 0.75);
    }
}
