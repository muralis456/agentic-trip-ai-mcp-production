package com.example.travel.jev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;

/**
 * Production policy for accepting typed JEV recommendations.
 *
 * <p>JEV/Tev1 is advisory intelligence. This policy decides whether a
 * recommendation is strong enough to influence the graph. The graph's
 * deterministic rules remain authoritative.</p>
 *
 * <p>Choice acceptance checks three signals: model-reported confidence
 * (distribution concentration), probability assigned to the selected option,
 * and margin over the runner-up.</p>
 */
@Component
public class JevDecisionPolicy {

    private final Threshold choice;
    private final Threshold modelRouting;
    private final Threshold ragRouting;
    private final Threshold providerRouting;
    private final Threshold goal;
    private final Threshold replan;
    private final Threshold hitl;
    private final double noulMinimumProbability;
    private final double scoreMinimumConfidence;

    public JevDecisionPolicy(
            @Value("${travel.jev.policy.choice.min-confidence:0.75}") double choiceConfidence,
            @Value("${travel.jev.policy.choice.min-probability:0.75}") double choiceProbability,
            @Value("${travel.jev.policy.choice.min-margin:0.15}") double choiceMargin,
            @Value("${travel.jev.policy.model-routing.min-confidence:0.75}") double modelConfidence,
            @Value("${travel.jev.policy.model-routing.min-probability:0.70}") double modelProbability,
            @Value("${travel.jev.policy.model-routing.min-margin:0.10}") double modelMargin,
            @Value("${travel.jev.policy.rag.min-confidence:0.70}") double ragConfidence,
            @Value("${travel.jev.policy.rag.min-probability:0.70}") double ragProbability,
            @Value("${travel.jev.policy.rag.min-margin:0.15}") double ragMargin,
            @Value("${travel.jev.policy.provider.min-confidence:0.75}") double providerConfidence,
            @Value("${travel.jev.policy.provider.min-probability:0.75}") double providerProbability,
            @Value("${travel.jev.policy.provider.min-margin:0.10}") double providerMargin,
            @Value("${travel.jev.policy.goal.min-confidence:0.80}") double goalConfidence,
            @Value("${travel.jev.policy.goal.min-probability:0.80}") double goalProbability,
            @Value("${travel.jev.policy.goal.min-margin:0.20}") double goalMargin,
            @Value("${travel.jev.policy.replan.min-confidence:0.75}") double replanConfidence,
            @Value("${travel.jev.policy.replan.min-probability:0.75}") double replanProbability,
            @Value("${travel.jev.policy.replan.min-margin:0.15}") double replanMargin,
            @Value("${travel.jev.policy.hitl.min-confidence:0.75}") double hitlConfidence,
            @Value("${travel.jev.policy.hitl.min-probability:0.90}") double hitlProbability,
            @Value("${travel.jev.policy.hitl.min-margin:0.75}") double hitlMargin,
            @Value("${travel.jev.policy.noul.min-probability:0.75}") double noulMinimumProbability,
            @Value("${travel.jev.policy.score.min-confidence:0.75}") double scoreMinimumConfidence) {

        this.choice = threshold(choiceConfidence, choiceProbability, choiceMargin);
        this.modelRouting = threshold(modelConfidence, modelProbability, modelMargin);
        this.ragRouting = threshold(ragConfidence, ragProbability, ragMargin);
        this.providerRouting = threshold(providerConfidence, providerProbability, providerMargin);
        this.goal = threshold(goalConfidence, goalProbability, goalMargin);
        this.replan = threshold(replanConfidence, replanProbability, replanMargin);
        this.hitl = threshold(hitlConfidence, hitlProbability, hitlMargin);
        this.noulMinimumProbability = bounded(noulMinimumProbability, "noul min probability");
        this.scoreMinimumConfidence = bounded(scoreMinimumConfidence, "score min confidence");
    }

    public boolean accepts(DecisionKind kind, JevDecisionClient.JevChoiceDecision decision) {
        if (decision == null || decision.choice() == null || decision.choice().isBlank()) {
            return false;
        }

        Threshold threshold = switch (kind) {
            case MODEL_ROUTING -> modelRouting;
            case RAG -> ragRouting;
            case PROVIDER -> providerRouting;
            case GOAL -> goal;
            case REPLAN -> replan;
            case HITL -> hitl;
            case CHOICE -> choice;
        };

        double selectedProbability = probabilityOf(decision.probabilities(), decision.choice());
        double decisionMargin = margin(decision.probabilities(), decision.choice());

        return decision.confidence() >= threshold.minConfidence()
                && selectedProbability >= threshold.minProbability()
                && decisionMargin >= threshold.minMargin();
    }

    public double selectedProbability(JevDecisionClient.JevChoiceDecision decision) {
        return probabilityOf(decision == null ? Map.of() : decision.probabilities(),
                decision == null ? "" : decision.choice());
    }

    public double margin(JevDecisionClient.JevChoiceDecision decision) {
        return margin(decision == null ? Map.of() : decision.probabilities(),
                decision == null ? "" : decision.choice());
    }

    public boolean acceptsNoul(double probability) {
        return probability >= noulMinimumProbability;
    }

    public boolean acceptsScore(double confidence) {
        return confidence >= scoreMinimumConfidence;
    }

    public record Threshold(double minConfidence, double minProbability, double minMargin) { }

    public enum DecisionKind {
        CHOICE, MODEL_ROUTING, RAG, PROVIDER, GOAL, REPLAN, HITL
    }

    private static Threshold threshold(double confidence, double probability, double margin) {
        return new Threshold(
                bounded(confidence, "min confidence"),
                bounded(probability, "min probability"),
                bounded(margin, "min margin"));
    }

    private static double probabilityOf(Map<String, Double> probabilities, String choice) {
        if (probabilities == null || probabilities.isEmpty() || choice == null) {
            return 0.0;
        }
        return probabilities.getOrDefault(choice, 0.0);
    }

    private static double margin(Map<String, Double> probabilities, String choice) {
        if (probabilities == null || probabilities.isEmpty() || choice == null) {
            return 0.0;
        }

        double selected = probabilityOf(probabilities, choice);
        double runnerUp = probabilities.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(choice))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && Double.isFinite(value))
                .max(Comparator.naturalOrder())
                .orElse(0.0);

        return Math.max(0.0, selected - runnerUp);
    }

    private static double bounded(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
        return value;
    }
}
