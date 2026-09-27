package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.tool.AirportLookupTool;
import com.example.travel.support.TripSlotHeuristics;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AirportResolverNode implements NodeAction<TravelState> {

    private final AirportLookupTool airportLookupTool;

    public AirportResolverNode(AirportLookupTool airportLookupTool) {
        this.airportLookupTool = airportLookupTool;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (!state.shouldExecuteTask("flights")) {
            return TravelState.trace(TravelGraphNodes.AIRPORT, "skip", "flights not requested");
        }
        String originQuery = TravelState.firstNonBlank(state.origin(), state.preferredAirport());
        // Prompt-only requests can reach this node without explicit DTO slots.
        // Recover a known destination from the current request before ever calling
        // the MCP resolver with a blank query. This also handles simple typos such
        // The request is expected to be normalized before graph execution;
        // this node only resolves the resulting canonical route.
        String destinationHint = TripSlotHeuristics.extractDestinationHint(state.userRequest());
        String destinationQuery = TripSlotHeuristics.normalizePlace(
                resolveDestinationQuery(state.destination(), destinationHint));
        String originIata = airportLookupTool.resolveIata(originQuery);

        Map<String, Object> updates = new LinkedHashMap<>();
        // Never invoke the MCP airport resolver with an empty destination.
        // Missing slots are a recoverable planning error, not a tool-call error.
        if (TravelState.isBlank(destinationQuery)) {
            updates.put(TravelState.ORIGIN_IATA, originIata);
            updates.putAll(TravelState.trace(TravelGraphNodes.AIRPORT, "warn",
                    originQuery + "->" + originIata + " / destination missing"));
            return updates;
        }

        String destinationIata = airportLookupTool.resolveIata(destinationQuery);
        // Persist the deterministic route recovered from the CURRENT request so
        // every downstream specialist (flight + hotel) sees exactly the same slots.
        if (TravelState.isBlank(state.origin())) {
            updates.put(TravelState.ORIGIN, originQuery);
        }
        if (TravelState.isBlank(state.destination()) && !TravelState.isBlank(destinationQuery)) {
            updates.put(TravelState.DESTINATION, destinationQuery);
        }
        updates.put(TravelState.ORIGIN_IATA, originIata);
        updates.put(TravelState.DESTINATION_IATA, destinationIata);
        String detail = originQuery + "->" + originIata + " / " + destinationQuery + "->" + destinationIata;
        updates.putAll(TravelState.trace(TravelGraphNodes.AIRPORT,
                originIata.isBlank() || destinationIata.isBlank() ? "warn" : "ok",
                detail));
        return updates;
    }

    private String resolveDestinationQuery(String destination, String hint) {
        // Prefer the clean destination extracted from the CURRENT request.
        String requestDestination = TripSlotHeuristics.normalizePlace(hint);
        if (!TravelState.isBlank(requestDestination)) {
            return requestDestination;
        }
        return TripSlotHeuristics.normalizePlace(destination);
    }
}
