package com.example.travel.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentObservabilityServiceTest {

    @Test
    void recordsMcpRetriesAndLlmTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentObservabilityService service =
                new AgentObservabilityService(registry, true, true, 0.01, 0.02);

        service.recordMcpCall("search_flights", "success", 120, 2);
        service.recordLlmUsage("ollama", "llama3.2:3b", "planner", 1000, 500);

        assertEquals(1.0, registry.get("travel.mcp.calls")
                .tag("tool", "search_flights").tag("outcome", "success").counter().count());
        assertEquals(2.0, registry.get("travel.mcp.retries")
                .tag("tool", "search_flights").counter().count());
        assertEquals(1000.0, registry.get("travel.llm.tokens")
                .tag("direction", "input").counter().count());
        assertEquals(500.0, registry.get("travel.llm.tokens")
                .tag("direction", "output").counter().count());
        assertEquals(0.02, registry.get("travel.llm.cost.usd")
                .tag("provider", "ollama").tag("model", "llama3.2:3b")
                .tag("operation", "planner").counter().count(), 0.000001);
    }
}
