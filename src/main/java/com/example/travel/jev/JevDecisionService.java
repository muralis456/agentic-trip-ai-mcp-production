package com.example.travel.jev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import com.example.travel.observability.AgentObservabilityService;

import java.util.Locale;
import java.util.Map;

/**
 * Application-level JEV decision facade.
 *
 * <p>JEV provides advisory intelligence. This facade applies a
 * decision-specific, deterministic acceptance policy before any graph node is
 * allowed to use the recommendation.</p>
 */
@Service
@ConditionalOnBean(JevDecisionClient.class)
public class JevDecisionService {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionService.class);

    private final JevDecisionClient client;
    private final JevDecisionPolicy policy;
    private final AgentObservabilityService observability;

    public JevDecisionService(
            JevDecisionClient client,
            JevDecisionPolicy policy,
            AgentObservabilityService observability) {
        this.client = client;
        this.policy = policy;
        this.observability = observability;
    }

    public Decision choose(
            Object state,
            String instructions,
            Map<String, String> criteria) {
        return choose(JevDecisionPolicy.DecisionKind.CHOICE, state, instructions, criteria);
    }

    public Decision choose(
            JevDecisionPolicy.DecisionKind kind,
            Object state,
            String instructions,
            Map<String, String> criteria) {

        long started = System.nanoTime();
        String decisionType = kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT);
        if (kind == null) {
            observability.recordJevDecision(decisionType, "error", "jev", elapsedMs(started));
            log.warn("jev.choice rejected reason=missing_decision_kind");
            return new Decision("", 0.0, Map.of(), false, "jev", "missing_decision_kind");
        }
        if (criteria == null || criteria.isEmpty()
                || criteria.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
            observability.recordJevDecision(decisionType, "error", "jev", elapsedMs(started));
            log.warn("jev.choice rejected kind={} reason=invalid_criteria", kind);
            return new Decision("", 0.0, Map.of(), false, "jev", "invalid_criteria");
        }
        try {
            JevDecisionClient.JevChoiceDecision decision = client.choose(state, instructions, criteria);
            JevDecisionPolicy.Acceptance acceptance = policy.acceptanceReason(kind, decision);
            if (acceptance.accepted() && !isAllowedChoice(criteria, decision.choice())) {
                acceptance = new JevDecisionPolicy.Acceptance(
                        false,
                        "choice_not_allowed",
                        acceptance.confidence(),
                        acceptance.selectedProbability(),
                        acceptance.margin());
            }
            boolean accepted = acceptance.accepted();
            double selectedProbability = acceptance.selectedProbability();
            double margin = acceptance.margin();
            String outcome = accepted ? "accepted" : "rejected_policy";

            observability.recordJevDecision(
                    decisionType,
                    outcome,
                    decision.model(),
                    elapsedMs(started));

            log.info(
                    "jev.choice decision kind={} choice={} confidence={} selectedProbability={} margin={} accepted={} acceptanceReason={} model={} durationMs={}",
                    kind,
                    safe(decision.choice()),
                    fmt(decision.confidence()),
                    fmt(selectedProbability),
                    fmt(margin),
                    accepted,
                    acceptance.reason(),
                    safe(decision.model()),
                    elapsedMs(started));

            return new Decision(
                    decision.choice(),
                    decision.confidence(),
                    decision.probabilities(),
                    accepted,
                    decision.model(),
                    acceptance.reason());
        } catch (RuntimeException ex) {
            String outcome = ex instanceof JevDecisionClient.JevUnavailableException
                    ? "unavailable"
                    : "error";
            observability.recordJevDecision(
                    decisionType,
                    outcome,
                    "jev",
                    elapsedMs(started));
            log.warn("jev.choice failed kind={} reason={}", kind, ex.getClass().getSimpleName());
            return new Decision("", 0.0, Map.of(), false, "jev-unavailable", outcome);
        }
    }

    public YesNoDecision yesNo(
            Object state,
            String instructions,
            String trueCriteria,
            String falseCriteria) {

        long started = System.nanoTime();
        try {
            JevDecisionClient.JevNoulDecision d =
                    client.yesNo(state, instructions, trueCriteria, falseCriteria);
            boolean accepted = isProbability(d.probability())
                    && policy.acceptsNoul(d.probability());
            String reason = noulReason(d.probability(), accepted);
            observability.recordJevDecision(
                    "noul",
                    accepted ? "accepted" : "rejected_policy",
                    "jev",
                    elapsedMs(started));
            log.info(
                    "jev.noul decision probability={} accepted={} acceptanceReason={} durationMs={}",
                    fmt(d.probability()),
                    accepted,
                    reason,
                    elapsedMs(started));
            return new YesNoDecision(d.probability(), accepted, reason);
        } catch (RuntimeException ex) {
            String reason = failureReason(ex);
            observability.recordJevDecision(
                    "noul",
                    reason,
                    "jev",
                    elapsedMs(started));
            log.warn("jev.noul failed reason={}", ex.getClass().getSimpleName());
            return new YesNoDecision(0.0, false, reason);
        }
    }

    public ScoreDecision score(
            Object state,
            String instructions,
            java.util.List<String> criteria) {

        long started = System.nanoTime();
        try {
            JevDecisionClient.JevScoreDecision d = client.score(state, instructions, criteria);
            boolean accepted = isProbability(d.score())
                    && isProbability(d.confidence())
                    && policy.acceptsScore(d.confidence());
            String reason = scoreReason(d.score(), d.confidence(), accepted);
            observability.recordJevDecision(
                    "score",
                    accepted ? "accepted" : "rejected_policy",
                    "jev",
                    elapsedMs(started));
            log.info(
                    "jev.score decision score={} confidence={} accepted={} acceptanceReason={} durationMs={}",
                    fmt(d.score()),
                    fmt(d.confidence()),
                    accepted,
                    reason,
                    elapsedMs(started));
            return new ScoreDecision(d.score(), d.confidence(), accepted, reason);
        } catch (RuntimeException ex) {
            String reason = failureReason(ex);
            observability.recordJevDecision(
                    "score",
                    reason,
                    "jev",
                    elapsedMs(started));
            log.warn("jev.score failed reason={}", ex.getClass().getSimpleName());
            return new ScoreDecision(0.0, 0.0, false, reason);
        }
    }

    private String noulReason(double probability, boolean accepted) {
        if (!isProbability(probability)) {
            return "invalid_probability";
        }
        return accepted ? "accepted" : "probability_below_threshold";
    }

    private String scoreReason(double score, double confidence, boolean accepted) {
        if (!isProbability(score)) {
            return "invalid_score";
        }
        if (!isProbability(confidence)) {
            return "invalid_confidence";
        }
        return accepted ? "accepted" : "confidence_below_threshold";
    }

    private static boolean isProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static String failureReason(RuntimeException ex) {
        return ex instanceof JevDecisionClient.JevUnavailableException ? "unavailable" : "error";
    }

    private static boolean isAllowedChoice(Map<String, String> criteria, String choice) {
        return choice != null && criteria.keySet().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(choice.trim()));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").trim();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    public record YesNoDecision(double probability, boolean accepted, String reason) {
        public YesNoDecision(double probability, boolean accepted) {
            this(probability, accepted, accepted ? "accepted" : "rejected");
        }
    }

    public record ScoreDecision(double score, double confidence, boolean accepted, String reason) {
        public ScoreDecision(double score, double confidence, boolean accepted) {
            this(score, confidence, accepted, accepted ? "accepted" : "rejected");
        }
    }

    public record Decision(
            String choice,
            double confidence,
            Map<String, Double> probabilities,
            boolean accepted,
            String model,
            String acceptanceReason) {
        public Decision(
                String choice,
                double confidence,
                Map<String, Double> probabilities,
                boolean accepted,
                String model) {
            this(choice, confidence, probabilities, accepted, model, accepted ? "accepted" : "rejected");
        }
    }
}
