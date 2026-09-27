package com.example.travel.graph.node;

import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.NodeFailureSupport;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.service.TripHistoryService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retrieves the current user's most recent saved trip from persistent memory.
 * This is deliberately a database-backed graph capability rather than a UI shortcut.
 */
@Component
public class HistoryNode implements NodeAction<TravelState> {

    private final TripHistoryService tripHistoryService;

    public HistoryNode(TripHistoryService tripHistoryService) {
        this.tripHistoryService = tripHistoryService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        try {
            Map<String, Object> updates = new LinkedHashMap<>();
            String userId = TravelState.firstNonBlank(state.userId());
            if (userId.isBlank()) {
                updates.put(TravelState.HISTORY_RESULT, "");
                updates.put(TravelState.FINAL_TIPS, "I couldn't find a saved trip in your trip history yet.");
                return updates;
            }
            String selection = state.historySelection();
            String result = tripHistoryService.findPlanJson(userId, selection);
            updates.put(TravelState.HISTORY_RESULT, result);
            if (result.isBlank()) {
                updates.put(TravelState.FINAL_TIPS, "I couldn't find a saved trip in your trip history yet.");
            }
            updates.putAll(TravelState.trace(TravelGraphNodes.HISTORY,
                    result.isBlank() ? "empty" : "ok",
                    result.isBlank() ? "no_saved_trip" : "saved_trip_retrieved:" + selection));
            GraphExecutionLogger.specialistResult(TravelGraphNodes.HISTORY, state,
                    result.isBlank() ? "empty" : "ok",
                    result.isBlank() ? "no_saved_trip" : "saved_trip_found");
            return updates;
        } catch (Exception ex) {
            return NodeFailureSupport.record(TravelGraphNodes.HISTORY, state, ex, true,
                    state.nodeFailure().getNodeRetryCount());
        }
    }
}
