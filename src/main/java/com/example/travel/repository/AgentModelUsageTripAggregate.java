package com.example.travel.repository;

/** Aggregated model usage grouped by trip/thread and model for cost reporting. */
public interface AgentModelUsageTripAggregate {
    String getThreadId();
    String getModel();
    Long getCalls();
    Long getInputTokens();
    Long getOutputTokens();
    Long getDurationMs();
}
