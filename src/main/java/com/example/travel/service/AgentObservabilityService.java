package com.example.travel.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Central application observability facade. Metric names and low-cardinality
 * tags are kept here so agent code does not scatter Micrometer details.
 *
 * Never pass prompt text, tool arguments, user IDs, conversation IDs or
 * exception messages as metric tags.
 */
@Service
public class AgentObservabilityService {

    private final MeterRegistry registry;

    public AgentObservabilityService(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordLlmCall(String operation, String model, int inputTokens,
                              int outputTokens, long durationMs, boolean success) {
        String op = normalize(operation, "unknown");
        String modelTag = normalize(model, "unknown");
        Counter.builder("agent.llm.calls")
                .tag("operation", op)
                .tag("model", modelTag)
                .tag("status", success ? "success" : "failure")
                .register(registry)
                .increment();

        registry.counter("agent.llm.tokens", "direction", "input", "model", modelTag, "operation", op)
                .increment(Math.max(0, inputTokens));
        registry.counter("agent.llm.tokens", "direction", "output", "model", modelTag, "operation", op)
                .increment(Math.max(0, outputTokens));

        Timer.builder("agent.llm.duration")
                .tag("operation", op)
                .tag("model", modelTag)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void recordLlmBudgetExhausted(String operation) {
        registry.counter("agent.llm.budget.exhausted", "operation", normalize(operation, "unknown")).increment();
    }

    public void recordMcpCall(String tool, String operation, long durationMs, boolean success) {
        String t = normalize(tool, "unknown");
        String op = normalize(operation, "unknown");
        Counter.builder("agent.mcp.calls")
                .tag("tool", t)
                .tag("operation", op)
                .tag("status", success ? "success" : "failure")
                .register(registry)
                .increment();
        Timer.builder("agent.mcp.duration")
                .tag("tool", t)
                .tag("operation", op)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void recordRetry(String component, String reason) {
        registry.counter("agent.retries",
                "component", normalize(component, "unknown"),
                "reason", normalize(reason, "unknown")).increment();
    }

    public void recordReplan(String reason) {
        registry.counter("agent.replans", "reason", normalize(reason, "unknown")).increment();
    }

    public void recordGoalOutcome(String outcome) {
        registry.counter("agent.goal.outcomes", "outcome", normalize(outcome, "unknown")).increment();
    }

    public void recordProviderCall(String provider, String operation, long durationMs, boolean success) {
        String p = normalize(provider, "unknown");
        String op = normalize(operation, "unknown");
        Counter.builder("agent.provider.calls")
                .tag("provider", p)
                .tag("operation", op)
                .tag("status", success ? "success" : "failure")
                .register(registry)
                .increment();
        Timer.builder("agent.provider.duration")
                .tag("provider", p)
                .tag("operation", op)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void recordPolicyDecision(String decision, String tool) {
        registry.counter("agent.policy.decisions",
                "decision", normalize(decision, "unknown"),
                "tool", normalize(tool, "unknown")).increment();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }
}
