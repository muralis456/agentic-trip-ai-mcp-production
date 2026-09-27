package com.example.travel.service;

import com.example.travel.model.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpResearchClient {

    private static final Logger log = LoggerFactory.getLogger(McpResearchClient.class);

    private final McpToolClient client;

    public McpResearchClient(McpToolClient client) {
        this.client = client;
    }

    public List<SearchHit> search(String query) {
        try {
            String safeQuery = query == null ? "" : query.trim();
            if (safeQuery.isBlank()) {
                return List.of();
            }
            JsonNode root = client.invokePreferred(
                    "search_travel_research",
                    "Travel web research and destination information",
                    safeQuery,
                    Map.of("query", safeQuery));
            List<SearchHit> hits = new ArrayList<>();
            JsonNode hitNodes = root == null ? null : root.path("hits");
            if (hitNodes == null || !hitNodes.isArray()) {
                return hits;
            }
            for (JsonNode node : hitNodes) {
                hits.add(new SearchHit(node.path("title").asString(""),
                    node.path("content").asString(node.path("snippet").asString("")),
                    node.path("url").asString("")));
            }
            return hits;
        } catch (Exception exception) {
            log.error("mcp.client.error client=McpResearchClient operation=search query={} errorType={} errorMessage={}",
                    query, exception.getClass().getName(), safeMessage(exception), exception);
            return List.of();
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
