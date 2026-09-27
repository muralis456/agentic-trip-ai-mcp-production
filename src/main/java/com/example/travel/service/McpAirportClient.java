package com.example.travel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpAirportClient {

    private static final Logger log = LoggerFactory.getLogger(McpAirportClient.class);

    private final McpToolClient client;

    public McpAirportClient(McpToolClient client) {
        this.client = client;
    }

    public String resolve(String cityOrCode) {
        if (cityOrCode == null || cityOrCode.isBlank()) {
            log.warn("mcp.client.skip client=McpAirportClient operation=resolve reason=blankAirportQuery");
            return "";
        }
        try {
            JsonNode root = client.invokePreferred("resolve_airport", "Resolve an airport or IATA code", "Resolve airport/IATA for: " + (cityOrCode == null ? "" : cityOrCode), Map.of("cityOrCode", cityOrCode == null ? "" : cityOrCode));
            return root.path("iata").asString("");
        } catch (Exception exception) {
            log.error("mcp.client.error client=McpAirportClient operation=resolve cityOrCode={} errorType={} errorMessage={}",
                    cityOrCode, exception.getClass().getName(), safeMessage(exception), exception);
            return "";
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
