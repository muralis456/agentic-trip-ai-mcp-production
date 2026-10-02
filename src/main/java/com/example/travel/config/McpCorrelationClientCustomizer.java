package com.example.travel.config;

import com.example.travel.mcp.McpCorrelationContext;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.stereotype.Component;

/**
 * Adds the AgenticTripAI correlation id to every outbound Streamable-HTTP MCP request.
 */
@Component
public class McpCorrelationClientCustomizer
        implements McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> {

    @Override
    public void customize(String name, HttpClientStreamableHttpTransport.Builder builder) {
        builder.httpRequestCustomizer(request -> McpCorrelationContext.current()
                .ifPresent(correlationId -> request.header("X-Correlation-ID", correlationId)));
    }
}
