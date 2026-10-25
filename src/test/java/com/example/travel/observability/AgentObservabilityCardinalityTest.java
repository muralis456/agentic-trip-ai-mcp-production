package com.example.travel.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentObservabilityCardinalityTest {

    @Test
    void freeFormRetryAndReplanReasonsCollapseToBoundedMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentObservabilityService service = new AgentObservabilityService(registry);

        service.recordRetry("mcp", "UnusualProviderException-unique-1");
        service.recordRetry("mcp", "UnusualProviderException-unique-2");
        service.recordReplan("user supplied free-form failure reason one");
        service.recordReplan("user supplied free-form failure reason two");

        assertEquals(2.0, registry.get("agent.retries")
                .tag("category", "mcp").tag("reason", "other").counter().count());
        assertEquals(1, registry.find("agent.retries").meters().size());
        assertEquals(2.0, registry.get("agent.replans")
                .tag("reason", "other").counter().count());
        assertEquals(1, registry.find("agent.replans").meters().size());
    }
}
