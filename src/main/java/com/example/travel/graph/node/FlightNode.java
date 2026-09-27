package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.ProvenanceEvent;
import com.example.travel.service.McpFlightSearchClient;
import com.example.travel.agent.FlightAgentService;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.NodeFailureSupport;
import com.example.travel.support.ToolFailureClassifier;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FlightNode implements NodeAction<TravelState> {

    private final FlightAgentService flightAgentService;

    public FlightNode(FlightAgentService flightAgentService) {
        this.flightAgentService = flightAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (!state.shouldExecuteTask("flights")) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put(TravelState.FLIGHTS, List.of());
            skip.putAll(TravelState.trace(TravelGraphNodes.FLIGHT, "skip", "not requested"));
            return skip;
        }
        try {
            FlightAgentService.FlightSearchResult result = flightAgentService.search(state);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(TravelState.FLIGHTS, result.flights());
            if (!TravelState.isBlank(result.originIata())) {
                updates.put(TravelState.ORIGIN_IATA, result.originIata());
            }
            if (!TravelState.isBlank(result.destinationIata())) {
                updates.put(TravelState.DESTINATION_IATA, result.destinationIata());
            }
                boolean usable = result.flights().stream().anyMatch(flight ->
                    flight != null && !"unavailable".equalsIgnoreCase(flight.getStatus()));
            updates.putAll(TravelState.trace(TravelGraphNodes.FLIGHT, "ok",
                    result.originIata() + " -> " + result.destinationIata() + " on " + state.departureDate()));
                GraphExecutionLogger.specialistResult(TravelGraphNodes.FLIGHT, state, usable ? "ok" : "warn",
                    "count=" + result.flights().size());
            updates.putAll(TravelState.provenance(new ProvenanceEvent(
                    "flights", "AviationStack", "", 0, result.originIata() + "->" + result.destinationIata())));
            return updates;
        } catch (Exception ex) {
            boolean retryable = ex instanceof McpFlightSearchClient.FlightProviderException providerException
                    ? providerException.retryable()
                    : ToolFailureClassifier.fromException(ex).isRetryable();
            return NodeFailureSupport.record(TravelGraphNodes.FLIGHT, state, ex, retryable,
                    state.nodeFailure().getNodeRetryCount());
        }
    }
}
