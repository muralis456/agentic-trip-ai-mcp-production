package com.example.travel.graph;

import com.example.travel.model.NodeFailureInfo;
import com.example.travel.model.ReplanAction;
import com.example.travel.model.ReplanStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps recorded specialist node failures to supervisor retry and replan actions.
 */
public final class NodeFailureRouting {

    private NodeFailureRouting() {
    }

    public static boolean hasRetryableFailure(TravelState state) {
        NodeFailureInfo failure = state.nodeFailure();
        return failure != null
                && !TravelState.isBlank(failure.getLastFailedNode())
                && failure.isRetryable();
    }

    public static ReplanStrategy replanForFailure(TravelState state) {
        ReplanStrategy strategy = new ReplanStrategy();
        NodeFailureInfo failure = state.nodeFailure();
        if (!hasRetryableFailure(state)) {
            return strategy;
        }
        String node = failure.getLastFailedNode();
        strategy.setReason("node_failure:" + node + " — " + failure.getLastError());
        List<String> actions = new ArrayList<>();
        if (TravelGraphNodes.FLIGHT.equals(node)) {
            strategy.setPriority("flight");
            actions.add(ReplanAction.CHEAPER_FLIGHT.wireName());
        } else if (TravelGraphNodes.HOTEL.equals(node)) {
            strategy.setPriority("hotel");
            actions.add(state.hotelCheaper()
                    ? ReplanAction.HOTEL_UPGRADE.wireName()
                    : ReplanAction.REDUCE_HOTEL_BUDGET.wireName());
        } else if (TravelGraphNodes.RESEARCH.equals(node)) {
            strategy.setPriority("research");
            actions.add(ReplanAction.REMOVE_EXPENSIVE_ATTRACTIONS.wireName());
        } else if (TravelGraphNodes.WEATHER.equals(node)) {
            strategy.setPriority("weather");
            actions.add(ReplanAction.GET_WEATHER_DETAILS.wireName());
        }
        strategy.setActions(actions);
        GraphExecutionLogger.replan(state, actions, Map.of("failedNode", node));
        return strategy;
    }

    public static void applySelectiveNeeds(Map<String, Object> updates, TravelState state) {
        if (!hasRetryableFailure(state)) {
            return;
        }
        String node = state.nodeFailure().getLastFailedNode();
        if (TravelGraphNodes.FLIGHT.equals(node)) {
            updates.put(TravelState.RUN_FLIGHTS, Boolean.TRUE);
        } else if (TravelGraphNodes.HOTEL.equals(node)) {
            updates.put(TravelState.RUN_HOTELS, Boolean.TRUE);
        } else if (TravelGraphNodes.RESEARCH.equals(node)) {
            updates.put(TravelState.RUN_RESEARCH, Boolean.TRUE);
        } else if (TravelGraphNodes.WEATHER.equals(node)) {
            updates.put(TravelState.RUN_WEATHER, Boolean.TRUE);
        }
        if (state.runBudget()) {
            updates.put(TravelState.RUN_BUDGET, Boolean.TRUE);
        }
    }
}
