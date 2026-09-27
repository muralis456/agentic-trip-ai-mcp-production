package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Passthrough after human input is written into state via {@code GraphInput.resume(Map)}.
 * Conditional edges from this node route Approve → Complete or Modify → Replan.
 */
@Component
public class HitlNode implements NodeAction<TravelState> {

    @Override
    public Map<String, Object> apply(TravelState state) {
        String decision = state.hitlDecision();
        return TravelState.trace(TravelGraphNodes.HITL, "waiting",
                "human decision=" + (TravelState.isBlank(decision) ? "pending" : decision));
    }
}
