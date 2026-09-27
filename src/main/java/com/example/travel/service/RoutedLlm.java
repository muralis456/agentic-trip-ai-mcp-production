package com.example.travel.service;

import com.example.travel.config.TravelModelsProperties;
import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.model.LlmExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.travel.exception.GraphStopRequestedException;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class RoutedLlm {

    private static final Logger log = LoggerFactory.getLogger(RoutedLlm.class);

    private final ChatClient chatClient;
    private final TravelModelsProperties models;
    private final AgentExecutionBudget executionBudget;
    private final int extractionMaxTokens;
    private final int plannerMaxTokens;
    private final int itineraryMaxTokens;
    private final int finalMaxTokens;

    public RoutedLlm(ChatClient chatClient,
                     TravelModelsProperties models,
                     AgentExecutionBudget executionBudget,
                     @Value("${travel.models.max-tokens.extraction:384}") int extractionMaxTokens,
                     @Value("${travel.models.max-tokens.planner:512}") int plannerMaxTokens,
                     @Value("${travel.models.max-tokens.itinerary:1400}") int itineraryMaxTokens,
                     @Value("${travel.models.max-tokens.final:700}") int finalMaxTokens) {
        this.chatClient = chatClient;
        this.models = models;
        this.executionBudget = executionBudget;
        this.extractionMaxTokens = Math.max(128, extractionMaxTokens);
        this.plannerMaxTokens = Math.max(128, plannerMaxTokens);
        this.itineraryMaxTokens = Math.max(256, itineraryMaxTokens);
        this.finalMaxTokens = Math.max(256, finalMaxTokens);
    }

    public String complete(AgentRole role, String system, String user) {
        return complete(role, system, user, (Object[]) null);
    }

    public String complete(AgentRole role, String system, String user, Object... tools) {
        return completeWithMeta(role, system, user, tools).content();
    }

    public LlmExecutionResult completeWithMeta(AgentRole role, String system, String user, Object... tools) {
        if (!executionBudget.tryConsumeLlm()) {
            log.warn("LLM execution budget exhausted; returning an empty model result so internal state is never exposed to the user");
            return new LlmExecutionResult("", "", "", 0);
        }
        String policy = ModelRoutingContext.get();
        ModelRoutingContext.Complexity complexity = ModelRoutingContext.getComplexity();
        String model = models.resolve(role, policy);
        java.util.List<String> modelCandidates = new java.util.ArrayList<>();
        modelCandidates.add(model);
        if (models.getFallbackModels() != null) {
            models.getFallbackModels().stream().filter(m -> m != null && !m.isBlank() && !m.trim().equals(model)).forEach(modelCandidates::add);
        }
        String toolNames = tools == null || tools.length == 0 ? ""
                : Arrays.stream(tools).map(tool -> tool.getClass().getSimpleName()).collect(Collectors.joining(","));
        log.info("[RoutedLLM] phase={} decision=MODEL_SELECTION model={} policy={} complexity={} tools={} purpose={} reason={}",
                role, model, policy, complexity, toolNames, purpose(role), routingReason(role, tools));
        long started = System.currentTimeMillis();
        var prompt = chatClient.prompt()
            .options(OllamaChatOptions.builder()
                .model(model)
                .temperature(models.temperature(role))
                .numPredict(maxTokens(role)))
            .system(system)
            .user(user);
        if (tools != null && tools.length > 0) {
            prompt = prompt.tools(tools);
        }
        Exception lastFailure = null;
        for (int candidateIndex = 0; candidateIndex < modelCandidates.size(); candidateIndex++) {
            String candidateModel = modelCandidates.get(candidateIndex);
            try {
                if (candidateIndex > 0) {
                    log.warn("[RoutedLLM] phase={} decision=MODEL_FALLBACK from={} to={}", role, model, candidateModel);
                }
                var candidatePrompt = chatClient.prompt()
                    .options(OllamaChatOptions.builder()
                        .model(candidateModel)
                        .temperature(models.temperature(role))
                        .numPredict(maxTokens(role)))
                    .system(system)
                    .user(user);
                if (tools != null && tools.length > 0) candidatePrompt = candidatePrompt.tools(tools);
                long candidateStarted = System.currentTimeMillis();
                var call = candidatePrompt.call();
                String content = call.content();
                long durationMs = System.currentTimeMillis() - candidateStarted;
                log.info("[RoutedLLM] phase={} decision=LLM_COMPLETED model={} durationMs={} responseStatus={} outputChars={}",
                        role, candidateModel, durationMs, content == null || content.isBlank() ? "EMPTY" : "SUCCESS", content == null ? 0 : content.length());
                int inputTokens = 0;
                int outputTokens = 0;
                try {
                    var response = call.chatResponse();
                    if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                        var usage = response.getMetadata().getUsage();
                        inputTokens = safeTokens(usage.getPromptTokens());
                        outputTokens = safeTokens(usage.getCompletionTokens());
                    }
                } catch (Exception ignored) { }
                LlmExecutionResult result = new LlmExecutionResult(content, candidateModel, toolNames, durationMs, inputTokens, outputTokens);
                LlmCallContext.record(result);
                return result;
            } catch (Exception ex) {
                long durationMs = System.currentTimeMillis() - started;
                if (ex instanceof GraphStopRequestedException || Thread.currentThread().isInterrupted() || isInterruptedCause(ex)) {
                    Thread.interrupted();
                    log.info("[RoutedLLM] phase={} decision=STOPPED_BY_USER model={} durationMs={}", role, candidateModel, durationMs);
                    if (ex instanceof GraphStopRequestedException stopRequested) throw stopRequested;
                    throw new GraphStopRequestedException(ex);
                }
                lastFailure = ex;
                log.warn("[RoutedLLM] phase={} decision=LLM_FAILED model={} durationMs={} errorType={} message={}", role, candidateModel, durationMs, ex.getClass().getSimpleName(), sanitizeLogMessage(ex.getMessage()));
            }
        }
        if (lastFailure instanceof RuntimeException runtimeException) throw runtimeException;
        throw new RuntimeException(lastFailure);
    }

    private static boolean isInterruptedCause(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof InterruptedException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * Human-readable task description for observability. This describes the application task,
     * not hidden model chain-of-thought.
     */
    private static String purpose(AgentRole role) {
        return switch (role) {
            case EXTRACT -> "extract structured travel entities and request slots";
            case PLANNER -> "resolve trip slots and construct the executable travel plan";
            case ITINERARY -> "generate the requested day-by-day itinerary";
            case FINAL -> "assemble the final user-facing travel response";
        };
    }

    private static String routingReason(AgentRole role, Object[] tools) {
        boolean hasTools = tools != null && tools.length > 0;
        return switch (role) {
            case EXTRACT -> hasTools
                    ? "structured extraction with tool support available"
                    : "structured extraction; no tool invocation required";
            case PLANNER -> "trip planning requires route, dates, budget and preference resolution";
            case ITINERARY -> "itinerary generation requires the resolved trip context and execution results";
            case FINAL -> "final response requires synthesis of completed agent results";
        };
    }

    private static String sanitizeLogMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private int maxTokens(AgentRole role) {
        return switch (role) {
            case PLANNER -> plannerMaxTokens;
            case ITINERARY -> itineraryMaxTokens;
            case FINAL -> finalMaxTokens;
            case EXTRACT -> extractionMaxTokens;
        };
    }

    private static int safeTokens(Integer value) {
        return value == null ? 0 : value;
    }
}
