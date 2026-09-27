package com.example.travel.tool;

import com.example.travel.model.SearchHit;
import com.example.travel.service.ExternalApiService;
import com.example.travel.service.McpResearchClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import java.util.stream.Collectors;

@Component
public class TavilySearchTool {

    private final ExternalApiService externalApiService;
    private final ObjectProvider<McpResearchClient> mcpResearchClient;

    public TavilySearchTool(ExternalApiService externalApiService,
                            ObjectProvider<McpResearchClient> mcpResearchClient) {
        this.externalApiService = externalApiService;
        this.mcpResearchClient = mcpResearchClient;
    }

    @Tool(description = "Web search via Tavily for destination research, attractions, food, and local tips. Always pass a concrete non-empty query string.")
    public String search(@ToolParam(description = "Required non-empty search query, e.g. 'best food in Tokyo'") String query) {
        McpResearchClient client = mcpResearchClient.getIfAvailable();
        if (client != null) {
            return client.search(query).stream()
                    .map(hit -> hit.getTitle() + ": " + hit.getContent() + " " + hit.getUrl())
                    .collect(Collectors.joining("\n"));
        }
        return externalApiService.searchTravelInfo(query);
    }

    public List<SearchHit> searchHits(String query) {
        McpResearchClient client = mcpResearchClient.getIfAvailable();
        if (client != null) {
            return client.search(query);
        }
        return externalApiService.searchTravelHits(query);
    }
}
