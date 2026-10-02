package com.example.travel.jev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import com.example.travel.observability.AgentObservabilityService;

import java.util.Map;

/**
 * Application-level Jev decision facade.
 *
 * Thresholds live in configuration/code, not inside prompts, so the policy
 * remains deterministic and reviewable.
 */
@Service
@ConditionalOnBean(JevDecisionClient.class)
public class JevDecisionService {

    private final JevDecisionClient client;
    private final double minimumConfidence;
    private final AgentObservabilityService observability;

    public JevDecisionService(
            JevDecisionClient client,
            @Value("${travel.jev.minimum-confidence:0.75}") double minimumConfidence,
            AgentObservabilityService observability) {
        this.client = client;
        this.minimumConfidence = minimumConfidence;
        this.observability = observability;
    }

    public Decision choose(
            Object state,
            String instructions,
            Map<String, String> criteria) {

        long started = System.nanoTime();
        try {
            JevDecisionClient.JevChoiceDecision decision = client.choose(state, instructions, criteria);
            boolean accepted = decision.confidence() >= minimumConfidence;
            observability.recordJevDecision(
                    "choice",
                    accepted ? "accepted" : "low_confidence",
                    decision.model(),
                    elapsedMs(started));
            return new Decision(
                    decision.choice(),
                    decision.confidence(),
                    decision.probabilities(),
                    accepted,
                    decision.model());
        } catch (RuntimeException ex) {
            // JEV is an optional decision accelerator. A missing API key,
            // exhausted credits, timeout, 4xx/5xx response, or malformed
            // response must never become a graph failure. Returning an
            // explicitly rejected decision lets each decision service apply
            // its deterministic Java fallback policy.
            String outcome = ex instanceof JevDecisionClient.JevUnavailableException
                    ? "unavailable"
                    : "error";
            observability.recordJevDecision("choice", outcome, "jev", elapsedMs(started));
            return new Decision("", 0.0, Map.of(), false, "jev-unavailable");
        }
    }

    public YesNoDecision yesNo(Object state, String instructions, String trueCriteria, String falseCriteria) {
        long started=System.nanoTime();
        JevDecisionClient.JevNoulDecision d = client.yesNo(state, instructions, trueCriteria, falseCriteria);
        observability.recordJevDecision("noul", d.probability() >= minimumConfidence ? "accepted" : "low_confidence", "jev", (System.nanoTime()-started)/1_000_000);
        return new YesNoDecision(d.probability(), d.probability() >= minimumConfidence);
    }

    public ScoreDecision score(Object state, String instructions, java.util.List<String> criteria) {
        long started=System.nanoTime();
        JevDecisionClient.JevScoreDecision d = client.score(state, instructions, criteria);
        observability.recordJevDecision("score", d.confidence() >= minimumConfidence ? "accepted" : "low_confidence", "jev", (System.nanoTime()-started)/1_000_000);
        return new ScoreDecision(d.score(), d.confidence(), d.confidence() >= minimumConfidence);
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
