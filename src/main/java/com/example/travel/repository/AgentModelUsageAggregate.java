package com.example.travel.repository;

/** Aggregated durable model usage for one user and time window. */
public interface AgentModelUsageAggregate {
    String getModel();
    Long getCalls();
    Long getErrors();
    Long getInputTokens();
    Long getOutputTokens();
    Long getDurationMs();
}