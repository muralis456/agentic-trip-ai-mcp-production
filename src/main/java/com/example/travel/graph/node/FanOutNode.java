package com.example.travel.graph.node;

import com.example.travel.graph.SpecialistRouter;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LangGraph parallel fan-out anchor. Downstream edges to flight/hotel/research/weather
 * execute concurrently when {@code addParallelNodeExecutor(FAN_OUT, ...)} is configured.
 */
@Component
public class FanOutNode implements NodeAction<TravelState> {

    @Override
    public Map<String, Object> apply(TravelState state) {
        GraphExecutionLogger.parallelFanOut(state);
        return TravelState.trace(TravelGraphNodes.FAN_OUT, "ok",
                "parallel=" + SpecialistRouter.plannedSpecialists(state));
    }
}
