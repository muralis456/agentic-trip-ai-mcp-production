package com.example.travel.jev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper;
    private final String model;

    public JevDecisionClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${travel.jev.base-url:https://api.typesafe.ai}") String baseUrl,
            @Value("${travel.jev.api-key:}") String apiKey,
            @Value("${travel.jev.model:jev-latest}") String model) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Jev is enabled but TRAVEL_JEV_API_KEY is not configured.");
        }

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    public JevChoiceDecision choose(
            Object state,
            String instructions,
            Map<String, String> criteria) {

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
        answer.path("probabilities").fields().forEachRemaining(entry ->
                probabilities.put(entry.getKey(), entry.getValue().asDouble()));

        return new JevChoiceDecision(response.path("model").asString(model), choice, confidence, probabilities);
    }

    public record JevChoiceDecision(
            String model,
            String choice,
            double confidence,
            Map<String, Double> probabilities) {
    }
}
