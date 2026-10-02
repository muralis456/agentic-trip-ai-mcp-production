package com.example.travel.jev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

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

    public JevDecisionService(
            JevDecisionClient client,
            @Value("${travel.jev.minimum-confidence:0.75}") double minimumConfidence) {
        this.client = client;
        this.minimumConfidence = minimumConfidence;
    }

    public Decision choose(
            Object state,
            String instructions,
            Map<String, String> criteria) {

        JevDecisionClient.JevChoiceDecision decision =
                client.choose(state, instructions, criteria);

        boolean accepted = decision.confidence() >= minimumConfidence;

        return new Decision(
                decision.choice(),
                decision.confidence(),
                decision.probabilities(),
                accepted,
                decision.model());
    }

    public record Decision(
            String choice,
            double confidence,
            Map<String, Double> probabilities,
            boolean accepted,
            String model) {
    }
}
