package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marks the trip plan as human-approved and closes the HITL wait.
 */
@Component
public class CompleteNode implements NodeAction<TravelState> {

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (state == null
                || state.goalEvaluation() == null
                || state.goalEvaluation().getStatus() != com.example.travel.model.GoalEvaluation.Status.ACHIEVED) {
            throw new IllegalStateException("Cannot complete before the goal is achieved.");
        }

        // Informational/non-trip workflows do not have a human approval
        // boundary. Finalization explicitly sets awaitingApproval=false for
        // those turns, so the graph is allowed to terminate normally.
        // Requiring an "approve" decision here used to make an otherwise
        // successful informational request fail in the terminal node.
        boolean tripPlanning = state.isTripPlanningWorkflow();
        boolean explicitlyApproved = "approve".equalsIgnoreCase(state.hitlDecision());
        if (tripPlanning && !explicitlyApproved) {
            throw new IllegalStateException(
                    "Cannot complete a trip plan before it is explicitly approved.");
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.AWAITING_APPROVAL, Boolean.FALSE);
        updates.putAll(TravelState.trace(TravelGraphNodes.COMPLETE, "ok",
                tripPlanning ? "plan approved" : "response completed"));
        return updates;
    }
}
