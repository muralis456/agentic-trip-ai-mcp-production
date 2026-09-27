package com.example.travel.graph.node;

import com.example.travel.graph.SpecialistRouter;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RouterNode implements NodeAction<TravelState> {

    @Override
    public Map<String, Object> apply(TravelState state) {
        String dest = SpecialistRouter.afterPlanner(state);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.DISPATCH_ROUTE, dest);
        updates.putAll(TravelState.trace(TravelGraphNodes.ROUTER, "ok",
                "goto=" + dest + " specialists=" + SpecialistRouter.plannedSpecialists(state)
                        + " knowledge=" + state.needsKnowledge()));
        return updates;
    }
}
