package com.example.travel.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Single entry point for application-level agent observability.
 *
 * Tags intentionally use bounded dimensions (operation, tool, node, provider,
 * outcome) so a user/request id is never turned into an unbounded Prometheus
 * label. Trace/span correlation is handled by Micrometer Tracing/OpenTelemetry.
 */
@Service
public class AgentObservabilityService {

    private final MeterRegistry registry;
    private final boolean enabled;
    private final boolean costEnabled;
    private final double inputCostPer1k;
    private final double outputCostPer1k;

    public AgentObservabilityService(
            MeterRegistry registry,
            @Value("${travel.observability.enabled:true}") boolean enabled,
            @Value("${travel.observability.cost.enabled:true}") boolean costEnabled,
            @Value("${travel.observability.cost.input-token-usd-per-1k:0}") double inputCostPer1k,
            @Value("${travel.observability.cost.output-token-usd-per-1k:0}") double outputCostPer1k) {
        this.registry = registry;
        this.enabled = enabled;
        this.costEnabled = costEnabled;
        this.inputCostPer1k = Math.max(0, inputCostPer1k);
        this.outputCostPer1k = Math.max(0, outputCostPer1k);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordAgentRun(String operation, String outcome, long durationMs) {
        if (!enabled) return;
        counter("travel.agent.runs", "operation", safe(operation), "outcome", safe(outcome)).increment();
        timer("travel.agent.run", "operation", safe(operation)).record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void recordAgentNode(String node, String outcome, long durationMs) {
        if (!enabled) return;
        counter("travel.agent.nodes", "node", safe(node), "outcome", safe(outcome)).increment();
        timer("travel.agent.node", "node", safe(node)).record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void recordMcpCall(String tool, String outcome, long durationMs, int retries) {
        if (!enabled) return;
        counter("travel.mcp.calls", "tool", safe(tool), "outcome", safe(outcome)).increment();
        timer("travel.mcp.call", "tool", safe(tool)).record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
        if (retries > 0) {
            counter("travel.mcp.retries", "tool", safe(tool)).increment(retries);
        }
    }

    public void recordRetry(String operation, String reason) {
        if (!enabled) return;
        counter("travel.agent.retries", "operation", safe(operation), "reason", safe(reason)).increment();
    }

    public void recordReplan(String reason, String outcome) {
        if (!enabled) return;
        counter("travel.agent.replans", "reason", safe(reason), "outcome", safe(outcome)).increment();
    }

    public void recordGoalOutcome(String outcome) {
        if (!enabled) return;
        counter("travel.agent.goal.outcomes", "outcome", safe(outcome)).increment();
    }

    /**
     * Token values are recorded as measurements, not labels. This method is
     * intentionally provider/model agnostic so Ollama, OpenAI-compatible,
     * Groq, or another Spring AI model can feed the same dashboard.
     */
    public void recordLlmUsage(String provider, String model, String operation,
                               long inputTokens, long outputTokens) {
        if (!enabled) return;
        long in = Math.max(0, inputTokens);
        long out = Math.max(0, outputTokens);
        counter("travel.llm.tokens", "provider", safe(provider), "model", safe(model),
                "operation", safe(operation), "direction", "input").increment(in);
        counter("travel.llm.tokens", "provider", safe(provider), "model", safe(model),
                "operation", safe(operation), "direction", "output").increment(out);
        counter("travel.llm.requests", "provider", safe(provider), "model", safe(model),
                "operation", safe(operation)).increment();
        if (costEnabled && (inputCostPer1k > 0 || outputCostPer1k > 0)) {
            double usd = (in / 1000.0) * inputCostPer1k + (out / 1000.0) * outputCostPer1k;
            registry.counter("travel.llm.cost.usd",
                    "provider", safe(provider), "model", safe(model),
                    "operation", safe(operation)).increment(usd);
        }
    }

    public void recordProviderCall(String provider, String operation, String outcome, long durationMs) {
        if (!enabled) return;
        counter("travel.provider.calls", "provider", safe(provider),
                "operation", safe(operation), "outcome", safe(outcome)).increment();
        timer("travel.provider.call", "provider", safe(provider),
                "operation", safe(operation)).record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(registry);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}
