package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.agent.ReplanAgentService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ReplanNode implements NodeAction<TravelState> {

    private final ReplanAgentService replanAgentService;

    public ReplanNode(ReplanAgentService replanAgentService) {
        this.replanAgentService = replanAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (retriesExhausted(state)) {
            Map<String, Object> updates = new LinkedHashMap<>();
            // Replan is also entered from HITL.  Only block internal recovery
            // retries; a new user modification is not a failed retry.
            updates.put(TravelState.SUPERVISOR_DECISION, TravelGraphNodes.ROUTE_PROCEED);
            updates.put(TravelState.RETRY_COUNT, state.retryCount());
            updates.put(TravelState.RUN_FLIGHTS, Boolean.FALSE);
            updates.put(TravelState.RUN_HOTELS, Boolean.FALSE);
            updates.put(TravelState.RUN_RESEARCH, Boolean.FALSE);
            updates.put(TravelState.RUN_WEATHER, Boolean.FALSE);
            updates.put(TravelState.RUN_BUDGET, Boolean.FALSE);
            updates.put(TravelState.RUN_ITINERARY, Boolean.FALSE);
            updates.putAll(TravelState.trace(TravelGraphNodes.REPLAN, "skipped",
                    "maxRetriesReached=" + state.maxRetries()));
            return updates;
        }

        Map<String, Object> updates = new LinkedHashMap<>(replanAgentService.decide(state));
        if (!"modify".equalsIgnoreCase(state.hitlDecision())) {
            int nextRetry = state.retryCount() + 1;
            updates.put(TravelState.RETRY_COUNT, nextRetry);
            if (nextRetry >= state.maxRetries()) {
                updates.put(TravelState.RUN_FLIGHTS, Boolean.FALSE);
                updates.put(TravelState.RUN_HOTELS, Boolean.FALSE);
                updates.put(TravelState.RUN_RESEARCH, Boolean.FALSE);
                updates.put(TravelState.RUN_WEATHER, Boolean.FALSE);
            }
        }
        updates.putAll(TravelState.trace(TravelGraphNodes.REPLAN, "retry",
                String.valueOf(updates.get(TravelState.REPLAN_NOTES))));
        return updates;
    }

    private boolean retriesExhausted(TravelState state) {
        return state.retryCount() >= state.maxRetries()
                && !"modify".equalsIgnoreCase(state.hitlDecision());
    }
}
