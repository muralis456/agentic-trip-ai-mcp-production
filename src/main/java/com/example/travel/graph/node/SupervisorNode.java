package com.example.travel.graph.node;

import com.example.travel.agent.SupervisorAgentService;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SupervisorNode implements NodeAction<TravelState> {

    private final SupervisorAgentService supervisorAgentService;

    public SupervisorNode(SupervisorAgentService supervisorAgentService) {
        this.supervisorAgentService = supervisorAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        Map<String, Object> updates = new LinkedHashMap<>(supervisorAgentService.review(state));
        String decision = String.valueOf(updates.getOrDefault(TravelState.SUPERVISOR_DECISION,
                TravelGraphNodes.ROUTE_PROCEED));
        updates.putAll(TravelState.trace(TravelGraphNodes.SUPERVISOR,
                TravelGraphNodes.ROUTE_RETRY.equals(decision) ? "retry" : "ok",
                "decision=" + decision));
        return updates;
    }
}
