package com.example.travel.model;

public record LlmExecutionResult(String content, String model, String toolCalls, long durationMs,
                                  int inputTokens, int outputTokens) {

    public LlmExecutionResult(String content, String model, String toolCalls, long durationMs) {
        this(content, model, toolCalls, durationMs, 0, 0);
    }

    public LlmExecutionResult {
        content = content == null ? "" : content;
        model = model == null ? "" : model;
        toolCalls = toolCalls == null ? "" : toolCalls;
    }
}
