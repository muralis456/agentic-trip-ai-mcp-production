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
 * <p>JEV/Tev1 provides advisory intelligence. This facade applies a
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
        try {
            JevDecisionClient.JevChoiceDecision decision = client.choose(state, instructions, criteria);
            JevDecisionPolicy.Acceptance acceptance = policy.acceptanceReason(kind, decision);
            boolean accepted = acceptance.accepted();
            double selectedProbability = acceptance.selectedProbability();
            double margin = acceptance.margin();
            String outcome = accepted ? "accepted" : "rejected_policy";

            observability.recordJevDecision(
                    kind.name().toLowerCase(),
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
                    decision.model());
        } catch (RuntimeException ex) {
            String outcome = ex instanceof JevDecisionClient.JevUnavailableException
                    ? "unavailable"
                    : "error";
            observability.recordJevDecision(
                    kind.name().toLowerCase(),
                    outcome,
                    "jev",
                    elapsedMs(started));
            log.warn("jev.choice failed kind={} reason={}", kind, ex.getClass().getSimpleName());
            return new Decision("", 0.0, Map.of(), false, "jev-unavailable");
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
            boolean accepted = policy.acceptsNoul(d.probability());
            observability.recordJevDecision(
                    "noul",
                    accepted ? "accepted" : "rejected_policy",
                    "jev",
                    elapsedMs(started));
            log.info(
                    "jev.noul decision probability={} accepted={} durationMs={}",
                    fmt(d.probability()),
                    accepted,
                    elapsedMs(started));
            return new YesNoDecision(d.probability(), accepted);
        } catch (RuntimeException ex) {
            observability.recordJevDecision(
                    "noul",
                    ex instanceof JevDecisionClient.JevUnavailableException ? "unavailable" : "error",
                    "jev",
                    elapsedMs(started));
            return new YesNoDecision(0.0, false);
        }
    }

    public ScoreDecision score(
            Object state,
            String instructions,
            java.util.List<String> criteria) {

        long started = System.nanoTime();
        try {
            JevDecisionClient.JevScoreDecision d = client.score(state, instructions, criteria);
            boolean accepted = policy.acceptsScore(d.confidence());
            observability.recordJevDecision(
                    "score",
                    accepted ? "accepted" : "rejected_policy",
                    "jev",
                    elapsedMs(started));
            log.info(
                    "jev.score decision score={} confidence={} accepted={} durationMs={}",
                    fmt(d.score()),
                    fmt(d.confidence()),
                    accepted,
                    elapsedMs(started));
            return new ScoreDecision(d.score(), d.confidence(), accepted);
        } catch (RuntimeException ex) {
            observability.recordJevDecision(
                    "score",
                    ex instanceof JevDecisionClient.JevUnavailableException ? "unavailable" : "error",
                    "jev",
                    elapsedMs(started));
            return new ScoreDecision(0.0, 0.0, false);
        }
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

    public record YesNoDecision(double probability, boolean accepted) { }

    public record ScoreDecision(double score, double confidence, boolean accepted) { }

    public record Decision(
            String choice,
            double confidence,
            Map<String, Double> probabilities,
            boolean accepted,
            String model) {
    }
}
