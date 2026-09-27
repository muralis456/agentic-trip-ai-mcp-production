package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.agent.ItineraryAgentService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ItineraryNode implements NodeAction<TravelState> {

    private final ItineraryAgentService itineraryAgentService;

    public ItineraryNode(ItineraryAgentService itineraryAgentService) {
        this.itineraryAgentService = itineraryAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (!state.shouldExecuteTask("itinerary")) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put(TravelState.ITINERARY, new com.example.travel.model.Itinerary());
            skip.putAll(TravelState.trace(TravelGraphNodes.ITINERARY, "skip", "not requested"));
            return skip;
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.ITINERARY, itineraryAgentService.build(state));
        updates.putAll(TravelState.trace(TravelGraphNodes.ITINERARY, "ok", state.nights() + " night itinerary"));
        return updates;
    }
}
