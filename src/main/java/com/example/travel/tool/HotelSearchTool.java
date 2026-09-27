package com.example.travel.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import com.example.travel.service.McpHotelSearchClient;

@Component
public class HotelSearchTool {

    private final TavilySearchTool tavilySearchTool;
    private final ObjectProvider<McpHotelSearchClient> mcpHotelSearchClient;

    public HotelSearchTool(TavilySearchTool tavilySearchTool,
                           ObjectProvider<McpHotelSearchClient> mcpHotelSearchClient) {
        this.tavilySearchTool = tavilySearchTool;
        this.mcpHotelSearchClient = mcpHotelSearchClient;
    }

    @Tool(description = "Search hotel and accommodation options for a destination. Set cheaper=true for budget stays.")
    public String search(
            @ToolParam(description = "Destination city or country") String destination,
            @ToolParam(description = "Travel style such as balanced, family, luxury, budget", required = false) String travelStyle,
            @ToolParam(description = "Prefer cheaper/budget hotels when true", required = false) Boolean cheaper,
            @ToolParam(description = "Optional maximum hotel price per night in INR", required = false) Double hotelBudget) {
        boolean budget = cheaper != null && cheaper;
        String style = travelStyle == null || travelStyle.isBlank() ? "balanced" : travelStyle;
        var mcpHotels = mcpHotelSearchClient.getIfAvailable();
        if (mcpHotels != null) {
            java.math.BigDecimal ceiling = hotelBudget == null ? null : java.math.BigDecimal.valueOf(hotelBudget);
            return mcpHotels.search(destination, style, budget, null, null, 2, 0, ceiling).stream()
                .map(com.example.travel.model.HotelOption::toDisplay)
                .collect(java.util.stream.Collectors.joining("\n"));
        }
        String query = (budget ? "Budget affordable hotels in " : "Best hotels in ") + destination
                + " including location, price range, family suitability, and guest ratings. Style=" + style
                + (hotelBudget == null ? "" : ". Maximum hotel budget INR=" + hotelBudget);
        return tavilySearchTool.search(query);
    }

    public java.util.List<com.example.travel.model.SearchHit> searchHits(String destination, String travelStyle, boolean cheaper) {
        var mcpHotels = mcpHotelSearchClient.getIfAvailable();
        if (mcpHotels != null) {
            return mcpHotels.search(destination, travelStyle, cheaper).stream()
                    .map(hotel -> new com.example.travel.model.SearchHit(hotel.getName(), hotel.getNotes(), ""))
                    .toList();
        }
        boolean budget = cheaper;
        String style = travelStyle == null || travelStyle.isBlank() ? "balanced" : travelStyle;
        String query = (budget ? "Budget affordable hotels in " : "Best hotels in ") + destination
                + " including location, price range, family suitability, and guest ratings. Style=" + style;
        return tavilySearchTool.searchHits(query);
    }
}
