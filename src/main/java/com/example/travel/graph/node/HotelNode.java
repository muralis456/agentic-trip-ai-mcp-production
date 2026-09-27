package com.example.travel.graph.node;

import com.example.travel.agent.HotelAgentService;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.NodeFailureSupport;
import com.example.travel.support.ToolFailureClassifier;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.ProvenanceEvent;
import com.example.travel.model.SearchHit;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HotelNode implements NodeAction<TravelState> {

    private final HotelAgentService hotelAgentService;

    public HotelNode(HotelAgentService hotelAgentService) {
        this.hotelAgentService = hotelAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (!state.shouldExecuteTask("hotels")) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put(TravelState.HOTELS, List.of());
            skip.putAll(TravelState.trace(TravelGraphNodes.HOTEL, "skip", "not requested"));
            return skip;
        }
        try {
            HotelAgentService.HotelSearchResult result = hotelAgentService.search(state);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(TravelState.HOTELS, result.hotels());
            updates.put(TravelState.HOTEL_FALLBACK_EXHAUSTED, result.fallbackExhausted());
            String outcome = result.hotels().isEmpty() ? "no_verified_results" : "ok";
            String traceReason = result.hotels().isEmpty()
                    ? "hotel search completed but returned no verified properties"
                    : (state.hotelCheaper() ? "cheaper hotel search" : "hotel search");
            updates.putAll(TravelState.trace(TravelGraphNodes.HOTEL, outcome, traceReason));
            GraphExecutionLogger.specialistResult(TravelGraphNodes.HOTEL, state, outcome,
                    "count=" + result.hotels().size());
            List<ProvenanceEvent> events = new ArrayList<>();
            for (SearchHit hit : result.hits()) {
                events.add(new ProvenanceEvent("hotels", "Tavily",
                        hit.getUrl() == null ? "" : hit.getUrl(),
                        hit.getScore(),
                        hit.getTitle()));
            }
            if (events.isEmpty() && !result.hotels().isEmpty()) {
                events.add(new ProvenanceEvent("hotels", "hotel-provider", "", 0, state.destination()));
            }
            updates.put(TravelState.PROVENANCE, events);
            return updates;
        } catch (Exception ex) {
            return NodeFailureSupport.record(TravelGraphNodes.HOTEL, state, ex,
                    ToolFailureClassifier.fromException(ex).isRetryable(),
                    state.nodeFailure().getNodeRetryCount());
        }
    }
}
