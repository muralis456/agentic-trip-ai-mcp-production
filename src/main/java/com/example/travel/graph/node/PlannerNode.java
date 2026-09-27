package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.agent.PlannerAgentService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PlannerNode implements NodeAction<TravelState> {

    private final PlannerAgentService plannerAgentService;

    public PlannerNode(PlannerAgentService plannerAgentService) {
        this.plannerAgentService = plannerAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        Map<String, Object> updates = new LinkedHashMap<>(plannerAgentService.plan(state));
        updates.putAll(TravelState.trace(TravelGraphNodes.PLANNER, "ok",
                String.valueOf(updates.get(TravelState.ORIGIN)) + " -> " + updates.get(TravelState.DESTINATION)));
        return updates;
    }
}
