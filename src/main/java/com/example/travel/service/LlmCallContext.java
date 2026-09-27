package com.example.travel.service;

import com.example.travel.model.LlmExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-thread LLM metadata so graph nodes can populate {@code AgentStep} without
 * threading RoutedLlm through every agent return type.
 */
public final class LlmCallContext {

    private static final ThreadLocal<List<LlmExecutionResult>> CALLS =
            ThreadLocal.withInitial(ArrayList::new);

    private LlmCallContext() {
    }

    public static void record(LlmExecutionResult result) {
        if (result != null) {
            CALLS.get().add(result);
        }
    }

    public static List<LlmExecutionResult> consume() {
        List<LlmExecutionResult> calls = new ArrayList<>(CALLS.get());
        CALLS.remove();
        return calls;
    }

    public static String joinedModels(List<LlmExecutionResult> calls) {
        return calls.stream()
                .map(LlmExecutionResult::model)
                .filter(model -> model != null && !model.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    public static String joinedTools(List<LlmExecutionResult> calls) {
        return calls.stream()
                .map(LlmExecutionResult::toolCalls)
                .filter(tools -> tools != null && !tools.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    public static int totalInputTokens(List<LlmExecutionResult> calls) {
        return calls.stream().mapToInt(LlmExecutionResult::inputTokens).sum();
    }

    public static int totalOutputTokens(List<LlmExecutionResult> calls) {
        return calls.stream().mapToInt(LlmExecutionResult::outputTokens).sum();
    }
}
