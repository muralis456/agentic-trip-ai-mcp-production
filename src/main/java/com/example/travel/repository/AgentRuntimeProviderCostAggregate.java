package com.example.travel.repository;

public interface AgentRuntimeProviderCostAggregate {
    String getThreadId();
    Long getCalls();
    Long getPricedCalls();
    Long getUnpricedCalls();
    Double getCostUsd();
}
