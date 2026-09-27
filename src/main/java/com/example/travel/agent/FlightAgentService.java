package com.example.travel.agent;

import com.example.travel.graph.TravelState;
import com.example.travel.model.FlightOption;
import com.example.travel.tool.AirportLookupTool;
import com.example.travel.tool.FlightSearchTool;
import com.example.travel.service.McpFlightSearchClient;
import com.example.travel.support.TripSlotHeuristics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.OffsetDateTime;
import java.time.LocalDate;

@Service
public class FlightAgentService {

    private static final Logger log = LoggerFactory.getLogger(FlightAgentService.class);

    private final FlightSearchTool flightSearchTool;
    private final AirportLookupTool airportLookupTool;
    private final ObjectProvider<McpFlightSearchClient> mcpFlightSearchClient;

    public FlightAgentService(FlightSearchTool flightSearchTool,
                              AirportLookupTool airportLookupTool,
                              ObjectProvider<McpFlightSearchClient> mcpFlightSearchClient) {
        this.flightSearchTool = flightSearchTool;
        this.airportLookupTool = airportLookupTool;
        this.mcpFlightSearchClient = mcpFlightSearchClient;
    }

    public FlightSearchResult search(TravelState state) {
        String originQuery = TravelState.firstNonBlank(state.origin(), state.preferredAirport());
        String destinationHint = TripSlotHeuristics.extractDestinationHint(state.userRequest());
        String destinationQuery = TripSlotHeuristics.normalizePlace(
                resolveDestinationQuery(state.destination(), destinationHint));

        String originIata = TravelState.firstNonBlank(state.originIata(), airportLookupTool.resolveIata(originQuery));
        String destinationIata = TravelState.firstNonBlank(state.destinationIata(),
                airportLookupTool.resolveIata(destinationQuery));

        if (TravelState.isBlank(destinationIata)) {
            log.warn("No airport found for destination={}", destinationQuery);
            return FlightSearchResult.unavailable(originIata, destinationIata,
                    "No airport could be resolved for " + destinationQuery + ". Flight options are unavailable.");
        }
        if (TravelState.isBlank(originIata)) {
            log.warn("No departure city found for flight search to destination={}", destinationQuery);
            return FlightSearchResult.unavailable(originIata, destinationIata,
                    "No departure city was provided, so flight options are unavailable.");
        }

        // Never call the provider with the same airport on both sides. This is
        // especially important for follow-ups: the destination can be inherited
        // from conversation memory while the current turn supplies a new origin.
        if (originIata.equalsIgnoreCase(destinationIata)) {
            log.warn("Skipping invalid same-airport flight route origin={} destination={} prompt={}",
                    originIata, destinationIata, state.userRequest());
            return FlightSearchResult.unavailable(originIata, destinationIata,
                    "Origin and destination are the same airport (" + originIata
                            + "). Please provide a different departure city.");
        }

        log.info("Flight agent searching roundTrip={} datesFlexible={} {} -> {}",
                state.roundTrip(), state.datesFlexible(), originIata, destinationIata);
        McpFlightSearchClient mcpClient = mcpFlightSearchClient.getIfAvailable();
        java.time.LocalDate outboundDate = state.datesFlexible() ? null : state.departureDate();
        // FINAL LIVE-PROVIDER GUARD: a live flight provider cannot search a past
        // departure date. Do not trust planner/LLM output, conversation memory,
        // normalized text, or checkpoint state at this boundary. Any past date
        // is stale state and is corrected to today before MCP is invoked.
        // Explicit past-date requests are still rejected by the MCP server, but
        // they must never be allowed to poison a normal live search with an old
        // date copied from conversation history.
        if (outboundDate != null && outboundDate.isBefore(java.time.LocalDate.now())) {
            java.time.LocalDate correctedDate = java.time.LocalDate.now();
            log.warn("flight.date.guard corrected stale departureDate={} to today={} origin={} destination={} userRequest={}",
                    outboundDate, correctedDate, originIata, destinationIata, state.userRequest());
            outboundDate = correctedDate;
        }
        List<FlightOption> flights = mcpClient == null
                ? flightSearchTool.search(originIata, destinationIata, outboundDate)
                : mcpClient.search(originIata, destinationIata, outboundDate, state.travelers(), state.userRequest());
        flights = normalizeResults(flights, "outbound", outboundDate, state.datesFlexible(), originIata, destinationIata);

        if (state.roundTrip()) {
            // A flexible-date round trip still needs a concrete provider probe date
            // for the reverse leg. TravelState already carries a deterministic
            // fallback return date (departure + 5 days). Do not send null here:
            // Ignav requires departure_date for every one-way search, and using
            // today's date again for NRT -> BLR can produce a provider-specific
            // HTTP 400 even though the outbound leg succeeded. This date is only
            // a provider probe; the user-facing trip remains date-flexible.
            java.time.LocalDate returnDate = state.returnDate();
            if (state.datesFlexible()) {
                log.info("flight.flexible-date return provider probe date={} route={} -> {}",
                        returnDate, destinationIata, originIata);
            }
            if (returnDate != null
                    && outboundDate != null
                    && returnDate.isBefore(outboundDate)
                    && !TripSlotHeuristics.hasDateHint(state.userRequest())
                    && !TripSlotHeuristics.hasDurationHint(state.userRequest())) {
                returnDate = outboundDate.plusDays(5);
            }
            List<FlightOption> returns = mcpClient == null
                    ? flightSearchTool.search(destinationIata, originIata, returnDate)
                    : mcpClient.search(destinationIata, originIata, returnDate, state.travelers(),
                    state.userRequest() + " Return journey");
            // A provider may ignore the requested reverse route. Never relabel a
            // BLR->NRT result as a return NRT->BLR flight; discard route-mismatched
            // records instead of presenting incorrect round-trip data.
            flights.addAll(normalizeResults(returns, "return", returnDate, state.datesFlexible(), destinationIata, originIata));
        }

        if (flights.isEmpty()) {
            return new FlightSearchResult(originIata, destinationIata, List.of());
        }
        return new FlightSearchResult(originIata, destinationIata, flights);
    }

    private List<FlightOption> normalizeResults(List<FlightOption> input, String direction,
                                                 java.time.LocalDate requestedDate, boolean flexible,
                                                 String expectedOrigin, String expectedDestination) {
        if (input == null) {
            return new java.util.ArrayList<>();
        }
        List<FlightOption> result = new java.util.ArrayList<>();
        for (FlightOption flight : input) {
            if (flight == null) continue;
            if ("unavailable".equalsIgnoreCase(flight.getStatus())) {
                // Keep one provider message only; the UI should not display it as a flight card.
                if (result.stream().noneMatch(f -> "unavailable".equalsIgnoreCase(f.getStatus()))) {
                    flight.setDirection(direction);
                    result.add(flight);
                }
                continue;
            }
            if (!routeMatches(flight, expectedOrigin, expectedDestination)) {
                log.warn("Discarding route-mismatched flight direction={} expected={}->{} actual={}->{} flight={}",
                        direction, expectedOrigin, expectedDestination, flight.getOrigin(), flight.getDestination(), flight.getFlightNumber());
                continue;
            }
            if (!flexible && requestedDate != null && hasExplicitDepartureDate(flight)
                    && extractDepartureDate(flight) != null
                    && !requestedDate.equals(extractDepartureDate(flight))) {
                log.warn("Discarding date-mismatched flight direction={} requestedDate={} actualDeparture={} flight={}",
                        direction, requestedDate, flight.getDepartureTime(), flight.getFlightNumber());
                continue;
            }
            flight.setDirection(direction);
            flight.setRequestedDate(requestedDate == null ? "" : requestedDate.toString());
            if (flexible) {
                flight.setNotes(mergeNote(flight.getNotes(), "live schedule · date flexible"));
            } else if (requestedDate != null) {
                String current = flight.getNotes() == null ? "" : flight.getNotes();
                if (!current.toLowerCase(java.util.Locale.ROOT).contains("date=" + requestedDate)) {
                    flight.setNotes(mergeNote(current, "requested date " + requestedDate + " not independently confirmed"));
                }
            }
            result.add(flight);
        }
        return result;
    }


    private boolean hasExplicitDepartureDate(FlightOption flight) {
        return flight != null && flight.getDepartureTime() != null && !flight.getDepartureTime().isBlank();
    }

    private LocalDate extractDepartureDate(FlightOption flight) {
        try {
            return OffsetDateTime.parse(flight.getDepartureTime()).toLocalDate();
        } catch (Exception ignored) {
            // If a provider returns a non-ISO time, keep the record and let the
            // existing "not independently confirmed" note communicate the gap.
            return null;
        }
    }

    private String mergeNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) return addition;
        if (existing.contains(addition)) return existing;
        return existing + " · " + addition;
    }

    private boolean routeMatches(FlightOption flight, String expectedOrigin, String expectedDestination) {
        if (flight == null) return false;
        String actualOrigin = normalizeCode(flight.getOrigin());
        String actualDestination = normalizeCode(flight.getDestination());
        String origin = normalizeCode(expectedOrigin);
        String destination = normalizeCode(expectedDestination);
        // Some providers return empty route fields. Keep those records because the
        // request itself supplied the authoritative route. Reject only explicit
        // mismatches, which protects return-flight direction.
        if (actualOrigin.isBlank() || actualDestination.isBlank()) return true;
        return actualOrigin.equals(origin) && actualDestination.equals(destination);
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String resolveDestinationQuery(String destination, String hint) {
        // A destination extracted from the CURRENT request is authoritative.
        // It prevents stale/over-broad planner text such as
        // "dubai within the budget 2L" from reaching airport lookup.
        String requestDestination = TripSlotHeuristics.normalizePlace(hint);
        if (!TravelState.isBlank(requestDestination)) {
            return requestDestination;
        }
        return TripSlotHeuristics.normalizePlace(destination);
    }

    /** Explicit result type (IDE-friendly; avoids flaky nested-record resolution). */
    public static final class FlightSearchResult {
        private final String originIata;
        private final String destinationIata;
        private final List<FlightOption> flights;

        public FlightSearchResult(String originIata, String destinationIata, List<FlightOption> flights) {
            this.originIata = originIata == null ? "" : originIata;
            this.destinationIata = destinationIata == null ? "" : destinationIata;
            this.flights = flights == null ? List.of() : List.copyOf(flights);
        }

        public String originIata() {
            return originIata;
        }

        public String destinationIata() {
            return destinationIata;
        }

        public List<FlightOption> flights() {
            return flights;
        }

        static FlightSearchResult unavailable(String originIata, String destinationIata, String message) {
            FlightOption option = new FlightOption();
            option.setNotes(message);
            option.setStatus("unavailable");
            return new FlightSearchResult(originIata, destinationIata, List.of(option));
        }
    }
}
