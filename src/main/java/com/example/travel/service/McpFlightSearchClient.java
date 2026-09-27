package com.example.travel.service;

import com.example.travel.model.FlightOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin MCP client adapter used during the incremental flight-tool migration.
 * The LangGraph graph still decides when this capability is invoked.
 */
@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpFlightSearchClient {

    private static final Logger log = LoggerFactory.getLogger(McpFlightSearchClient.class);

    private final McpToolClient mcpToolClient;
    private final ObjectMapper objectMapper;

    public McpFlightSearchClient(McpToolClient mcpToolClient, ObjectMapper objectMapper) {
        this.mcpToolClient = mcpToolClient;
        this.objectMapper = objectMapper;
    }

    public List<FlightOption> search(String origin, String destination, LocalDate departureDate, int passengers) {
        return search(origin, destination, departureDate, passengers, "Flight search for " + origin + " to " + destination);
    }

    public List<FlightOption> search(String origin, String destination, LocalDate departureDate, int passengers, String userInput) {
        // Absolute last line of defence: this is the exact boundary where the
        // request is serialized and sent to the MCP server. A past date must
        // never leave the client, regardless of how it entered graph state.
        if (departureDate != null && departureDate.isBefore(LocalDate.now())) {
            LocalDate correctedDate = LocalDate.now();
            log.warn("mcp.client.date.guard corrected departureDate={} to today={} origin={} destination={}",
                    departureDate, correctedDate, origin, destination);
            departureDate = correctedDate;
        }
        try {
            // Ignav requires a concrete departure_date even when the user did not
            // specify an exact travel date. Keep the user intent date-flexible, but
            // use a provider-only probe date at the MCP boundary. This date is never
            // written back into TravelState or presented as the user's requested date.
            LocalDate providerDepartureDate = departureDate != null
                    ? departureDate
                    : LocalDate.now().plusDays(1);
            if (departureDate == null) {
                log.info("mcp.client.date.guard using provider probe date={} for flexible search origin={} destination={}",
                        providerDepartureDate, origin, destination);
            }
            Map<String, Object> input = Map.of(
                    "origin", origin,
                    "destination", destination,
                    "departureDate", providerDepartureDate.toString(),
                    "returnDate", "",
                    "passengers", Math.max(1, passengers));
                return parse(mcpToolClient.invokePreferred("search_flights", "Live flight schedule search", userInput, input));
        } catch (FlightProviderException exception) {
            log.error("mcp.client.error client=McpFlightSearchClient operation=search origin={} destination={} departureDate={} passengers={} errorCode={} retryable={} errorMessage={}",
                    origin, destination, departureDate, passengers, exception.errorCode(), exception.retryable(), exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            log.error("mcp.client.error client=McpFlightSearchClient operation=search origin={} destination={} departureDate={} passengers={} errorType={} errorMessage={}",
                    origin, destination, departureDate, passengers, exception.getClass().getName(), safeMessage(exception), exception);
            throw new FlightProviderException("PROVIDER_UNAVAILABLE",
                    "MCP flight search is unavailable: " + safeMessage(exception), true, exception);
        }
    }

    private List<FlightOption> parse(JsonNode root) throws Exception {
        if (!root.path("success").asBoolean(false)) {
            String errorCode = root.path("errorCode").asString("PROVIDER_ERROR");
            String message = root.path("message").asString("MCP flight search failed.");
            boolean retryable = !isNonRetryableProviderError(errorCode, message);
            throw new FlightProviderException(errorCode, message, retryable, null);
        }

        List<FlightOption> flights = new ArrayList<>();
        for (JsonNode flight : root.path("flights")) {
            FlightOption option = new FlightOption();
            option.setFlightNumber(flight.path("flightNumber").asString(""));
            option.setAirline(flight.path("airline").asString(""));
            option.setOrigin(flight.path("origin").asString(""));
            option.setDestination(flight.path("destination").asString(""));
            option.setDepartureTime(flight.path("departureScheduled").asString(""));
            option.setArrivalTime(flight.path("arrivalScheduled").asString(""));
            option.setStatus(flight.path("status").asString("unknown"));
            option.setNotes(flight.path("notes").asString(""));
            flights.add(option);
        }
        return flights;
    }

    private FlightOption unavailable(String message) {
        FlightOption option = new FlightOption();
        option.setStatus("unavailable");
        option.setNotes(message);
        return option;
    }

    private boolean isNonRetryableProviderError(String errorCode, String message) {
        String code = errorCode == null ? "" : errorCode.toUpperCase(java.util.Locale.ROOT);
        String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        // Provider-specific 4xx errors are not all equivalent. A combined MCP
        // response such as FLIGHT_PROVIDERS_UNAVAILABLE may contain one transient
        // provider failure (429) and one request-specific failure (400). Do not
        // classify the aggregate solely by searching the human-readable message.
        // The server is responsible for provider fallback; the client should retry
        // only when the aggregate itself is retryable.
        if (code.contains("INVALID_") || code.contains("IGNAV_HTTP_400")
                || code.contains("IGNAV_HTTP_422") || code.contains("PROVIDER_CONFIGURATION")) {
            return true; // non-retryable
        }
        return code.contains("AUTH") || code.contains("401") || code.contains("403")
                || code.contains("NOT_FOUND") || code.contains("404")
                || text.contains("rate limit") || text.contains("quota") || text.contains("too many requests")
                || text.contains("circuit-open") || text.contains("circuit open")
                || text.contains("provider_http_429");
    }

    public static final class FlightProviderException extends RuntimeException {
        private final String errorCode;
        private final boolean retryable;

        public FlightProviderException(String errorCode, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode == null ? "PROVIDER_ERROR" : errorCode;
            this.retryable = retryable;
        }

        public String errorCode() {
            return errorCode;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
