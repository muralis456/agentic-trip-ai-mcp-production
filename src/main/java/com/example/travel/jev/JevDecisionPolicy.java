package com.example.travel.jev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Goal routing is choice-aware: a REPLAN recommendation uses the REPLAN
 * threshold while HITL uses the stricter HITL threshold. This prevents the
 * generic GOAL threshold from incorrectly rejecting a valid recovery decision.</p>
 */
@Component
public class JevDecisionPolicy {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionPolicy.class);

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
            @Value("${travel.jev.policy.choice.min-confidence:0.0}") double choiceConfidence,
            @Value("${travel.jev.policy.choice.min-probability:0.75}") double choiceProbability,
            @Value("${travel.jev.policy.choice.min-margin:0.15}") double choiceMargin,
            @Value("${travel.jev.policy.model-routing.min-confidence:0.0}") double modelConfidence,
            @Value("${travel.jev.policy.model-routing.min-probability:0.60}") double modelProbability,
            @Value("${travel.jev.policy.model-routing.min-margin:0.15}") double modelMargin,
            @Value("${travel.jev.policy.rag.min-confidence:0.0}") double ragConfidence,
            @Value("${travel.jev.policy.rag.min-probability:0.70}") double ragProbability,
            @Value("${travel.jev.policy.rag.min-margin:0.15}") double ragMargin,
            @Value("${travel.jev.policy.provider.min-confidence:0.0}") double providerConfidence,
            @Value("${travel.jev.policy.provider.min-probability:0.75}") double providerProbability,
            @Value("${travel.jev.policy.provider.min-margin:0.10}") double providerMargin,
            @Value("${travel.jev.policy.goal.min-confidence:0.80}") double goalConfidence,
            @Value("${travel.jev.policy.goal.min-probability:0.80}") double goalProbability,
            @Value("${travel.jev.policy.goal.min-margin:0.20}") double goalMargin,
            @Value("${travel.jev.policy.replan.min-confidence:0.0}") double replanConfidence,
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

        log.info(
                "jev.policy.effective choice={}/{}/{} modelRouting={}/{}/{} rag={}/{}/{} provider={}/{}/{} goal={}/{}/{} replan={}/{}/{} hitl={}/{}/{} noul={} score={}",
                fmt(choice.minConfidence()), fmt(choice.minProbability()), fmt(choice.minMargin()),
                fmt(modelRouting.minConfidence()), fmt(modelRouting.minProbability()), fmt(modelRouting.minMargin()),
                fmt(ragRouting.minConfidence()), fmt(ragRouting.minProbability()), fmt(ragRouting.minMargin()),
                fmt(providerRouting.minConfidence()), fmt(providerRouting.minProbability()), fmt(providerRouting.minMargin()),
                fmt(goal.minConfidence()), fmt(goal.minProbability()), fmt(goal.minMargin()),
                fmt(replan.minConfidence()), fmt(replan.minProbability()), fmt(replan.minMargin()),
                fmt(hitl.minConfidence()), fmt(hitl.minProbability()), fmt(hitl.minMargin()),
                fmt(noulMinimumProbability), fmt(scoreMinimumConfidence));
    }

    public boolean accepts(DecisionKind kind, JevDecisionClient.JevChoiceDecision decision) {
        return acceptanceReason(kind, decision).accepted();
    }

    /**
     * Returns the exact acceptance result so production logs can explain why
     * a recommendation was accepted or rejected instead of exposing only false.
     */
    public Acceptance acceptanceReason(DecisionKind kind, JevDecisionClient.JevChoiceDecision decision) {
        if (decision == null || decision.choice() == null || decision.choice().isBlank()) {
            return new Acceptance(false, "missing_choice", 0.0, 0.0, 0.0);
        }

        Threshold threshold = thresholdFor(kind, decision.choice());
        double selectedProbability = probabilityOf(decision.probabilities(), decision.choice());
        double decisionMargin = margin(decision.probabilities(), decision.choice());
        double confidence = decision.confidence();

        if (!Double.isFinite(confidence)) {
            return new Acceptance(false, "invalid_confidence", confidence, selectedProbability, decisionMargin);
        }
        if (!Double.isFinite(selectedProbability)) {
            return new Acceptance(false, "invalid_selected_probability", confidence, selectedProbability, decisionMargin);
        }
        if (!Double.isFinite(decisionMargin)) {
            return new Acceptance(false, "invalid_probability_margin", confidence, selectedProbability, decisionMargin);
        }
        if (confidence < threshold.minConfidence()) {
            return new Acceptance(false, "confidence_below_threshold", confidence, selectedProbability, decisionMargin);
        }
        if (selectedProbability < threshold.minProbability()) {
            return new Acceptance(false, "selected_probability_below_threshold", confidence, selectedProbability, decisionMargin);
        }
        if (decisionMargin < threshold.minMargin()) {
            return new Acceptance(false, "probability_margin_below_threshold", confidence, selectedProbability, decisionMargin);
        }

        return new Acceptance(true, "accepted", confidence, selectedProbability, decisionMargin);
    }

    private Threshold thresholdFor(DecisionKind kind, String choice) {
        if (kind == DecisionKind.GOAL) {
            if ("REPLAN".equalsIgnoreCase(choice)) return replan;
            if ("HITL".equalsIgnoreCase(choice)) return hitl;
        }

        return switch (kind) {
            case MODEL_ROUTING -> modelRouting;
            case RAG -> ragRouting;
            case PROVIDER -> providerRouting;
            case GOAL -> goal;
            case REPLAN -> replan;
            case HITL -> hitl;
            case CHOICE -> choice;
        };
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

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public record Acceptance(
            boolean accepted,
            String reason,
            double confidence,
            double selectedProbability,
            double margin) {
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
