package com.example.travel.service;

import com.example.travel.model.FlightOption;
import com.example.travel.model.SearchHit;
import com.example.travel.support.FlightSupport;
import com.example.travel.support.ToolFailureClassifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExternalApiService {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AgentExecutionBudget executionBudget;

    @Value("${travel.tavily.api-url:}")
    private String tavilyUrl;

    @Value("${travel.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${travel.aviation.api-url:}")
    private String aviationUrl;

    @Value("${travel.aviation.api-key:}")
    private String aviationApiKey;

    /**
     * Free AviationStack plans reject {@code flight_date} with 403 function_access_restricted.
     * Keep false unless you are on a paid plan that supports historical/future date search.
     */
    @Value("${travel.aviation.include-flight-date:false}")
    private boolean includeFlightDate;

    public ExternalApiService(RestTemplate restTemplate, ObjectMapper objectMapper, AgentExecutionBudget executionBudget) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.executionBudget = executionBudget;
    }

    public String searchTravelInfo(String query) {
        if (query == null || query.isBlank()) {
            log.warn("Tavily search skipped: query was null/blank (often from a tool call with missing args)");
            return "Tavily search skipped: query must be a non-empty string.";
        }
        if (!executionBudget.tryConsumeTavily()) {
            return "Additional web research is unavailable right now.";
        }
        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            log.warn("Tavily API key is missing");
            return "Tavily API key is missing. Please set TAVILY_API_KEY environment variable.";
        }

        String cleanQuery = query.trim();
        log.debug("Searching travel info via Tavily for query={}", cleanQuery);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + tavilyApiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("query", cleanQuery);
        body.put("search_depth", "basic");
        body.put("max_results", 5);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            return withRetry("Tavily", () -> {
                ResponseEntity<String> response = restTemplate.exchange(tavilyUrl, HttpMethod.POST, request, String.class);
                log.info("Tavily search completed for query={}", cleanQuery);
                return response.getBody();
            });
        } catch (Exception exception) {
            log.warn("Tavily search failed for query={}", cleanQuery, exception);
            return "Tavily search failed: " + exception.getMessage();
        }
    }

    public List<SearchHit> searchTravelHits(String query) {
        String body = searchTravelInfo(query);
        List<SearchHit> hits = new ArrayList<>();
        if (body == null || body.isBlank() || !body.strip().startsWith("{")) {
            log.debug("Tavily hits skipped: response was not JSON ({})",
                    body == null ? "null" : body.substring(0, Math.min(80, body.length())));
            return hits;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return hits;
            }
            for (JsonNode result : results) {
                SearchHit hit = new SearchHit(
                        text(result, "title"),
                        text(result, "content"),
                        text(result, "url", ""));
                hit.setScore(result.path("score").asDouble(0));
                hits.add(hit);
            }
        } catch (Exception exception) {
            log.warn("Could not parse Tavily response into search hits");
        }
        return hits;
    }

    public List<FlightOption> fetchFlights(String departureIata, String arrivalIata, String date) {
        List<FlightOption> flights = new ArrayList<>();
        if (aviationApiKey == null || aviationApiKey.isBlank()) {
            log.warn("AviationStack API key is missing");
            flights.add(unavailable("AviationStack API key is missing. Please set AVIATIONSTACK_API_KEY."));
            return flights;
        }

        // Free plans: never send flight_date (causes 403 function_access_restricted).
        // Paid plans can enable travel.aviation.include-flight-date=true.
        boolean useDate = includeFlightDate && date != null && !date.isBlank();
        try {
            List<FlightOption> result = callAviationStack(departureIata, arrivalIata, useDate ? date : null, date);
            if (!useDate) {
                annotateLiveSchedule(result, date);
            }
            return result;
        } catch (HttpStatusCodeException exception) {
            String body = exception.getResponseBodyAsString();
            boolean dateRestricted = useDate && (exception.getStatusCode().value() == 403
                    || (body != null && body.contains("function_access_restricted")));
            if (dateRestricted) {
                log.warn("AviationStack rejected requested flight_date for {} -> {}; not substituting live flights",
                        departureIata, arrivalIata);
                flights.add(unavailable("Flight availability for the requested date could not be confirmed by the configured AviationStack plan."));
                return flights;
            }
            log.warn("AviationStack flights API returned status={} for from={}, to={}",
                    exception.getStatusCode(), departureIata, arrivalIata);
            flights.add(unavailable("AviationStack flight search is temporarily unavailable. Requested flight dates were not confirmed."));
            return flights;
        } catch (Exception exception) {
            log.warn("AviationStack flights API failed for from={}, to={}", departureIata, arrivalIata, exception);
            flights.add(unavailable("AviationStack flight search failed: " + exception.getMessage()));
            return flights;
        }
    }

    private List<FlightOption> callAviationStack(String departureIata, String arrivalIata,
                                                  String flightDateParam, String tripDate) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(aviationUrl + "/flights")
                .queryParam("access_key", aviationApiKey)
                .queryParam("dep_iata", departureIata)
                .queryParam("arr_iata", arrivalIata);
        if (flightDateParam != null && !flightDateParam.isBlank()) {
            builder.queryParam("flight_date", flightDateParam);
        }
        String url = builder.build().toUriString();
        log.info("Calling AviationStack flights API: from={}, to={}, flight_date={}",
                departureIata, arrivalIata, flightDateParam == null ? "(omitted for free plan)" : flightDateParam);
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return parseFlights(response.getBody(), departureIata, arrivalIata, tripDate);
    }

    private void annotateLiveSchedule(List<FlightOption> flights, String requestedDate) {
        if (flights == null) {
            return;
        }
        for (FlightOption option : flights) {
            if ("unavailable".equalsIgnoreCase(option.getStatus())) {
                continue;
            }
            // Keep notes short for UI — avoid repeating the free-plan disclaimer on every row.
            if (requestedDate != null && !requestedDate.isBlank()) {
                option.setNotes("live · trip " + requestedDate);
            } else {
                option.setNotes("live schedule");
            }
        }
    }

    private List<FlightOption> parseFlights(String responseBody, String origin, String destination, String tripDate) {
        List<FlightOption> flights = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("error").isObject()) {
                String message = text(root.path("error"), "message", "AviationStack error");
                String code = text(root.path("error"), "code", "");
                flights.add(unavailable("AviationStack error " + code + ": " + message));
                return flights;
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                flights.add(unavailable("No flight records were returned by AviationStack"
                        + (tripDate == null ? "." : " for " + tripDate + ".")));
                return flights;
            }
            List<FlightOption> parsed = new ArrayList<>();
            int count = Math.min(data.size(), 40);
            for (int index = 0; index < count; index++) {
                JsonNode flight = data.get(index);
                String flightDate = text(flight, "flight_date", "");
                FlightOption option = new FlightOption(
                        text(flight.path("flight"), "iata"),
                        text(flight.path("airline"), "name"),
                        text(flight.path("departure"), "iata", origin),
                        text(flight.path("arrival"), "iata", destination),
                        text(flight.path("departure"), "scheduled"),
                        text(flight.path("arrival"), "scheduled"),
                        text(flight, "flight_status"),
                        flightDate.isBlank() ? "" : "date=" + flightDate);
                parsed.add(option);
            }
            return FlightSupport.dedupePreferDate(parsed, tripDate, 5);
        } catch (Exception exception) {
            log.warn("Could not parse AviationStack response", exception);
            flights.add(unavailable("AviationStack returned an unreadable flight response."));
            return flights;
        }
    }

    private <T> T withRetry(String label, Supplier<T> action) {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return action.get();
            } catch (HttpStatusCodeException exception) {
                last = exception;
                var code = ToolFailureClassifier.fromHttp(exception.getStatusCode().value(),
                        exception.getResponseBodyAsString());
                log.warn("{} attempt {} failed code={}: {}", label, attempt, code, exception.getMessage());
                if (!code.isRetryable()) {
                    throw exception;
                }
            } catch (ResourceAccessException exception) {
                last = exception;
                log.warn("{} attempt {} timed out: {}", label, attempt, exception.getMessage());
            }
        }
        if (last instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(label + " failed after retries", last);
    }

    private FlightOption unavailable(String notes) {
        FlightOption option = new FlightOption();
        option.setNotes(notes);
        option.setStatus("unavailable");
        return option;
    }

    private String text(JsonNode node, String field) {
        return text(node, field, "Unavailable");
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asString(null);
        return value == null || value.isBlank() ? fallback : value;
    }
}
