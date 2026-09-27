package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CancelNode implements NodeAction<TravelState> {

    @Override
    public Map<String, Object> apply(TravelState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.AWAITING_APPROVAL, Boolean.FALSE);
        updates.put(TravelState.HITL_DECISION, "reject");
        String tips = TravelState.firstNonBlank(state.finalTips(), "")
                + "\n\n---\nHuman rejected this plan. Graph ended without applying it.";
        updates.put(TravelState.FINAL_TIPS, tips);
        updates.putAll(TravelState.trace(TravelGraphNodes.CANCEL, "ok", "plan rejected"));
        return updates;
    }
}
