package com.example.travel.jev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed decision client.
 *
 * Primary provider can be TypeSafe JEV or local Ollama System One.
 * When TypeSafe is unavailable, the client can transparently fall back to a
 * local Ollama Jev-style model (for example tev1:4b). Java decision services
 * remain the final deterministic fallback.
 */
@Service
@ConditionalOnProperty(prefix = "travel.jev", name = "enabled", havingValue = "true")
public class JevDecisionClient {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionClient.class);
    private final RestClient primaryClient;
    private final RestClient fallbackClient;
    private final String primaryModel;
    private final String fallbackModel;
    private final boolean primaryAvailable;
    private final boolean fallbackAvailable;
    private final String primaryProvider;
    private final String fallbackProvider;

    public JevDecisionClient(
            RestClient.Builder restClientBuilder,
            @Value("${travel.jev.provider:typesafe}") String provider,
            @Value("${travel.jev.base-url:https://api.typesafe.ai}") String baseUrl,
            @Value("${travel.jev.api-key:}") String apiKey,
            @Value("${travel.jev.model:jev-latest}") String model,
            @Value("${travel.jev.fallback-provider:ollama}") String fallbackProvider,
            @Value("${travel.jev.fallback-base-url:http://localhost:11434}") String fallbackBaseUrl,
            @Value("${travel.jev.fallback-api-key:ollama}") String fallbackApiKey,
            @Value("${travel.jev.fallback-model:tev1:4b}") String fallbackModel) {

        this.primaryProvider = provider == null || provider.isBlank() ? "typesafe" : provider.trim().toLowerCase();
        this.fallbackProvider = fallbackProvider == null || fallbackProvider.isBlank()
                ? "none"
                : fallbackProvider.trim().toLowerCase();
        this.primaryModel = model;
        this.fallbackModel = fallbackModel;

        boolean primaryConfigured = isConfigured(this.primaryProvider, baseUrl, apiKey);
        this.primaryAvailable = primaryConfigured;
        this.primaryClient = primaryConfigured
                ? buildClient(restClientBuilder, baseUrl, apiKey)
                : null;

        boolean fallbackConfigured = isConfigured(this.fallbackProvider, fallbackBaseUrl, fallbackApiKey);
        this.fallbackAvailable = fallbackConfigured;
        this.fallbackClient = fallbackConfigured
                ? buildClient(restClientBuilder, fallbackBaseUrl, fallbackApiKey)
                : null;
    }

    public JevChoiceDecision choose(Object state, String instructions, Map<String, String> criteria) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", instructions);
        question.put("criteria", criteria);

        ProviderResponse response = callWithFallback(request(state, Map.of("decision", question)));
        JsonNode answer = validateAnswer(response.response(), "choice");

        String choice = answer.path("choice").asString("").trim();
        double confidence = answer.path("confidence").asDouble(0.0);
        if (choice.isBlank()) {
            throw new IllegalStateException("Typed decision provider returned an empty choice.");
        }

        Map<String, Double> probabilities = new LinkedHashMap<>();
        answer.path("probabilities").properties().forEach(entry ->
                probabilities.put(entry.getKey(), entry.getValue().asDouble()));

        return new JevChoiceDecision(
                response.response().path("model").asString(response.model()),
                choice,
                confidence,
                probabilities);
    }

    public JevNoulDecision yesNo(Object state, String instructions, String trueCriteria, String falseCriteria) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "noul");
        question.put("instructions", instructions);
        question.put("criteria", Map.of("true", trueCriteria, "false", falseCriteria));

        JsonNode answer = validateAnswer(
                callWithFallback(request(state, Map.of("decision", question))).response(),
                "noul");

        return new JevNoulDecision(answer.path("noul").asDouble(0.0));
    }

    public JevScoreDecision score(Object state, String instructions, java.util.List<String> criteria) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "score");
        question.put("instructions", instructions);
        question.put("criteria", criteria);

        JsonNode answer = validateAnswer(
                callWithFallback(request(state, Map.of("decision", question))).response(),
                "score");

        return new JevScoreDecision(
                answer.path("score").asDouble(0.0),
                answer.path("confidence").asDouble(0.0));
    }

    private Map<String, Object> request(Object state, Map<String, Object> questions) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("state", state);
        request.put("questions", questions);
        return request;
    }

    private ProviderResponse callWithFallback(Map<String, Object> request) {
        RuntimeException primaryFailure = null;

        if (primaryAvailable) {
            try {
                log.debug("jev.provider.primary-attempt provider={} model={}", primaryProvider, primaryModel);
                return invoke(primaryClient, primaryModel, request, primaryProvider);
            } catch (RuntimeException ex) {
                primaryFailure = ex;
                log.warn("jev.provider.primary-failed provider={} model={} reason={}", primaryProvider, primaryModel, ex.getClass().getSimpleName());
            }
        }

        if (fallbackAvailable) {
            try {
                log.info("jev.provider.fallback-attempt provider={} model={}", fallbackProvider, fallbackModel);
                return invoke(fallbackClient, fallbackModel, request, fallbackProvider);
            } catch (RuntimeException fallbackFailure) {
                if (primaryFailure != null) {
                    fallbackFailure.addSuppressed(primaryFailure);
                }
                log.error("jev.provider.fallback-failed provider={} model={} reason={}", fallbackProvider, fallbackModel, fallbackFailure.getClass().getSimpleName());
                throw new JevUnavailableException(
                        "Typed decision providers are unavailable. Primary=" +
                        primaryProvider + ", fallback=" + fallbackProvider,
                        fallbackFailure);
            }
        }

        throw new JevUnavailableException(
                "No typed decision provider is configured. Configure TypeSafe or a local Ollama fallback.",
                primaryFailure);
    }

    private ProviderResponse invoke(
            RestClient client,
            String model,
            Map<String, Object> request,
            String provider) {

        Map<String, Object> payload = new LinkedHashMap<>(request);
        payload.put("model", model);

        log.debug("jev.provider.request provider={} model={}", provider, model);
        JsonNode response = client.post()
                .uri("/v1/systemone")
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || response.path("answers").isMissingNode()) {
            throw new IllegalStateException(
                    "Typed decision provider returned an empty/invalid response: " + provider);
        }

        String resolvedModel = response.path("model").asString(model);
        log.info("jev.provider.response provider={} model={} responseValid=true", provider, resolvedModel);
        return new ProviderResponse(response, resolvedModel, provider);
    }

    private static JsonNode validateAnswer(JsonNode response, String expectedType) {
        JsonNode answers = response.path("answers");
        JsonNode answer = answers.path("decision");

        if (answer.isMissingNode() || answer.isNull()) {
            throw new IllegalStateException(
                    "Typed decision provider returned no answer for question 'decision'.");
        }

        // TypeSafe/Ollama System One responses identify the question by name
        // and return the type-specific answer fields directly. The response
        // does not contain answers.decision.type.
        String answerField = switch (expectedType) {
            case "choice" -> "choice";
            case "noul" -> "noul";
            case "score" -> "score";
            default -> throw new IllegalArgumentException("Unsupported decision type: " + expectedType);
        };

        if (answer.path(answerField).isMissingNode()) {
            throw new IllegalStateException(
                    "Typed decision provider returned an invalid " + expectedType +
                    " answer for question 'decision'.");
        }

        return answer;
    }

    private static RestClient buildClient(
            RestClient.Builder builder,
            String baseUrl,
            String apiKey) {

        RestClient.Builder configured = builder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            configured.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        return configured.build();
    }

    private static boolean isConfigured(String provider, String baseUrl, String apiKey) {
        if (provider == null || provider.isBlank() || "none".equals(provider)) {
            return false;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        return !"typesafe".equals(provider) || (apiKey != null && !apiKey.isBlank());
    }

    private record ProviderResponse(JsonNode response, String model, String provider) { }

    public record JevNoulDecision(double probability) { }
    public record JevScoreDecision(double score, double confidence) { }

    public record JevChoiceDecision(
            String model,
            String choice,
            double confidence,
            Map<String, Double> probabilities) { }

    public static final class JevUnavailableException extends IllegalStateException {
        public JevUnavailableException(String message) {
            super(message);
        }

        public JevUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
