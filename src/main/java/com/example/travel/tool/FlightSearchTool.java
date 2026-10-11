package com.example.travel.tool;

import com.example.travel.model.FlightOption;
import com.example.travel.service.ExternalApiService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.travel.service.McpFlightSearchClient;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class FlightSearchTool {

    private final ExternalApiService externalApiService;
    private final ObjectProvider<McpFlightSearchClient> mcpFlightSearchClient;

    @Autowired
    public FlightSearchTool(ExternalApiService externalApiService,
                            ObjectProvider<McpFlightSearchClient> mcpFlightSearchClient) {
        this.externalApiService = externalApiService;
        this.mcpFlightSearchClient = mcpFlightSearchClient;
    }

    /** Backward-compatible constructor used by direct-tool unit tests. */
    public FlightSearchTool(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
        this.mcpFlightSearchClient = new ObjectProvider<>() {
            @Override public McpFlightSearchClient getObject(Object... args) { throw new IllegalStateException("MCP flight client is not available"); }
            @Override public McpFlightSearchClient getIfAvailable() { return null; }
            @Override public McpFlightSearchClient getIfUnique() { return null; }
            @Override public java.util.Iterator<McpFlightSearchClient> iterator() { return java.util.Collections.emptyIterator(); }
        };
    }

    public List<FlightOption> search(String originIata, String destinationIata, LocalDate departureDate) {
        McpFlightSearchClient mcp = mcpFlightSearchClient.getIfAvailable();
        if (mcp != null) {
            return mcp.search(originIata, destinationIata, departureDate, 1,
                    "Flight search for " + originIata + " to " + destinationIata, "");
        }
        // Direct AviationStack calls are disabled by default. This legacy route is
        // retained only for installations that explicitly enable the adapter.
        return externalApiService.fetchFlights(originIata, destinationIata,
                departureDate == null ? null : departureDate.toString());
    }

    @Tool(description = "Search flights through the MCP provider fallback chain. "
            + "AviationStack is disabled; configured alternatives such as Ignav and SerpApi Google Flights are tried in order. "
            + "Pass only real IATA codes resolved from the airport database.")
    public String searchFlights(
            @ToolParam(description = "Departure IATA code, e.g. BLR") String originIata,
            @ToolParam(description = "Arrival IATA code, e.g. NRT") String destinationIata,
            @ToolParam(description = "Trip departure date yyyy-MM-dd", required = false) String departureDate) {
        LocalDate date = null;
        if (departureDate != null && !departureDate.isBlank()) {
            try {
                date = LocalDate.parse(departureDate.trim());
            } catch (Exception ignored) {
                // Invalid/missing dates must remain unknown. Never silently
                // substitute today's date because it can make live results look
                // like results for a user-requested travel date.
                date = null;
            }
        }
        List<FlightOption> flights = search(originIata, destinationIata, date);
        if (flights == null || flights.isEmpty()) {
            return "No flights found.";
        }
        return flights.stream().map(FlightOption::toDisplay).collect(Collectors.joining("\n"));
    }
}
