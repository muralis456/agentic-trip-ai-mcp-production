package com.example.travel.repository;

public interface AgentRuntimeEventAggregate {
    String getEventType();
    String getComponent();
    String getStatus();
    Long getCount();
    Double getAverageDurationMs();
    Long getRetried();
}
