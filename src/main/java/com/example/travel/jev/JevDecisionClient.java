package com.example.travel.jev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin client for TypeSafe Jev/System One.
 *
 * Jev returns typed decisions rather than generated text. The application
 * remains responsible for thresholds, authorization and the resulting action.
 */
@Service
@ConditionalOnProperty(prefix = "travel.jev", name = "enabled", havingValue = "true")
public class JevDecisionClient {

    private final RestClient restClient;
    private final String model;
    private final boolean apiKeyConfigured;

    public JevDecisionClient(
            RestClient.Builder restClientBuilder,
            @Value("${travel.jev.base-url:https://api.typesafe.ai}") String baseUrl,
            @Value("${travel.jev.api-key:}") String apiKey,
            @Value("${travel.jev.model:jev-latest}") String model) {

        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        this.model = model;

        // JEV is an optional decision accelerator. A missing API key must never
        // prevent the main application from starting; decision services will
        // fall back to their deterministic Java policies instead.
        if (!apiKeyConfigured) {
            this.restClient = null;
            return;
        }

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public JevChoiceDecision choose(
            Object state,
            String instructions,
            Map<String, String> criteria) {

        ensureAvailable();

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", instructions);
        question.put("criteria", criteria);

        Map<String, Object> questions = Map.of("decision", question);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("state", state);
        request.put("model", model);
        request.put("questions", questions);

        JsonNode response = restClient.post()
                .uri("/v1/systemone")
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Jev returned an empty response.");
        }

        JsonNode answer = response.path("answers").path("decision");
        if (!"choice".equals(answer.path("type").asString())) {
            throw new IllegalStateException("Jev returned a non-choice decision: " + response);
        }

        String choice = answer.path("choice").asString("").trim();
        double confidence = answer.path("confidence").asDouble(0.0);

        if (choice.isBlank()) {
            throw new IllegalStateException("Jev returned an empty choice.");
        }

        Map<String, Double> probabilities = new LinkedHashMap<>();
        answer.path("probabilities").properties().forEach(entry ->
                probabilities.put(entry.getKey(), entry.getValue().asDouble()));

        return new JevChoiceDecision(response.path("model").asString(model), choice, confidence, probabilities);
    }

    public JevNoulDecision yesNo(Object state, String instructions, String trueCriteria, String falseCriteria) {
        ensureAvailable();
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "noul");
        question.put("instructions", instructions);
        question.put("criteria", Map.of("true", trueCriteria, "false", falseCriteria));
        JsonNode answer = call(state, Map.of("decision", question)).path("answers").path("decision");
        if (!"noul".equals(answer.path("type").asString())) throw new IllegalStateException("Jev returned a non-noul decision.");
        return new JevNoulDecision(answer.path("noul").asDouble(0.0));
    }

    public JevScoreDecision score(Object state, String instructions, java.util.List<String> criteria) {
        ensureAvailable();
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "score"); question.put("instructions", instructions); question.put("criteria", criteria);
        JsonNode answer = call(state, Map.of("decision", question)).path("answers").path("decision");
        if (!"score".equals(answer.path("type").asString())) throw new IllegalStateException("Jev returned a non-score decision.");
        return new JevScoreDecision(answer.path("score").asDouble(0.0), answer.path("confidence").asDouble(0.0));
    }

    private void ensureAvailable() {
        if (!apiKeyConfigured || restClient == null) {
            throw new JevUnavailableException(
                    "Jev is unavailable because TRAVEL_JEV_API_KEY is not configured. " +
                    "Continue with the deterministic Java decision policy.");
        }
    }

    private JsonNode call(Object state, Map<String, Object> questions) {
        ensureAvailable();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("state", state); request.put("model", model); request.put("questions", questions);
        JsonNode response = restClient.post().uri("/v1/systemone").body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("answers").isMissingNode()) throw new IllegalStateException("Jev returned an empty/invalid response.");
        return response;
    }

    public record JevNoulDecision(double probability) { }
    public record JevScoreDecision(double score, double confidence) { }

    public record JevChoiceDecision(
            String model,
            String choice,
            double confidence,
            Map<String, Double> probabilities) {
    }

    public static final class JevUnavailableException extends IllegalStateException {
        public JevUnavailableException(String message) {
            super(message);
        }
    }
}
