package com.example.travel.graph;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for LangGraph4j parallel fan-out / fan-in semantics:
 * FAN_OUT → Flight/Hotel/Research/Weather → Supervisor must join once.
 */
class FanOutSupervisorJoinIT {

    private final List<String> executionOrder = new CopyOnWriteArrayList<>();

    @Test
    void fanOutJoinsAtSupervisorExactlyOnce() throws GraphStateException {
        ObjectStreamStateSerializer<TravelState> serializer = new ObjectStreamStateSerializer<>(TravelState::new);
        ExecutorService parallel = Executors.newFixedThreadPool(4);

        StateGraph<TravelState> graph = new StateGraph<>(TravelState.SCHEMA, serializer)
                .addNode(TravelGraphNodes.FAN_OUT, node_async(state -> {
                    executionOrder.add(TravelGraphNodes.FAN_OUT);
                    return TravelState.trace(TravelGraphNodes.FAN_OUT, "ok", "fan-out");
                }))
                .addNode(TravelGraphNodes.FLIGHT, node_async(state -> {
                    executionOrder.add(TravelGraphNodes.FLIGHT);
                    return TravelState.trace(TravelGraphNodes.FLIGHT, "ok", "flight");
                }))
                .addNode(TravelGraphNodes.HOTEL, node_async(state -> {
                    executionOrder.add(TravelGraphNodes.HOTEL);
                    return TravelState.trace(TravelGraphNodes.HOTEL, "ok", "hotel");
                }))
                .addNode(TravelGraphNodes.RESEARCH, node_async(state -> {
                    executionOrder.add(TravelGraphNodes.RESEARCH);
                    return TravelState.trace(TravelGraphNodes.RESEARCH, "ok", "research");
                }))
                .addNode(TravelGraphNodes.WEATHER, node_async(state -> {
                    executionOrder.add(TravelGraphNodes.WEATHER);
                    return TravelState.trace(TravelGraphNodes.WEATHER, "ok", "weather");
                }))
                .addNode(TravelGraphNodes.SUPERVISOR, node_async(state -> {
                    executionOrder.add(TravelGraphNodes.SUPERVISOR);
                    return TravelState.trace(TravelGraphNodes.SUPERVISOR, "ok", "supervisor");
                }))
                .addNode("done", node_async(state -> Map.of()))
                .addEdge(START, TravelGraphNodes.FAN_OUT)
                .addEdge(TravelGraphNodes.FAN_OUT, TravelGraphNodes.FLIGHT)
                .addEdge(TravelGraphNodes.FAN_OUT, TravelGraphNodes.HOTEL)
                .addEdge(TravelGraphNodes.FAN_OUT, TravelGraphNodes.RESEARCH)
                .addEdge(TravelGraphNodes.FAN_OUT, TravelGraphNodes.WEATHER)
                .addEdge(TravelGraphNodes.FLIGHT, TravelGraphNodes.SUPERVISOR)
                .addEdge(TravelGraphNodes.HOTEL, TravelGraphNodes.SUPERVISOR)
                .addEdge(TravelGraphNodes.RESEARCH, TravelGraphNodes.SUPERVISOR)
                .addEdge(TravelGraphNodes.WEATHER, TravelGraphNodes.SUPERVISOR)
                .addEdge(TravelGraphNodes.SUPERVISOR, "done")
                .addEdge("done", END);

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .addParallelNodeExecutor(TravelGraphNodes.FAN_OUT, parallel)
                .build();

        CompiledGraph<TravelState> compiled = graph.compile(CompileConfig.builder().build());
        compiled.invoke(Map.of(
                TravelState.RUN_FLIGHTS, Boolean.TRUE,
                TravelState.RUN_HOTELS, Boolean.TRUE,
                TravelState.RUN_RESEARCH, Boolean.TRUE,
                TravelState.RUN_WEATHER, Boolean.TRUE), runnableConfig);

        parallel.shutdown();

        assertEquals(1, count(executionOrder, TravelGraphNodes.FAN_OUT));
        assertEquals(1, count(executionOrder, TravelGraphNodes.FLIGHT));
        assertEquals(1, count(executionOrder, TravelGraphNodes.HOTEL));
        assertEquals(1, count(executionOrder, TravelGraphNodes.RESEARCH));
        assertEquals(1, count(executionOrder, TravelGraphNodes.WEATHER));
        assertEquals(1, count(executionOrder, TravelGraphNodes.SUPERVISOR),
                "Supervisor must execute exactly once after parallel fan-in; order=" + executionOrder);

        int supervisorIndex = executionOrder.indexOf(TravelGraphNodes.SUPERVISOR);
        assertTrue(supervisorIndex > executionOrder.indexOf(TravelGraphNodes.FLIGHT));
        assertTrue(supervisorIndex > executionOrder.indexOf(TravelGraphNodes.HOTEL));
        assertTrue(supervisorIndex > executionOrder.indexOf(TravelGraphNodes.RESEARCH));
        assertTrue(supervisorIndex > executionOrder.indexOf(TravelGraphNodes.WEATHER));
    }

    private static int count(List<String> order, String node) {
        int total = 0;
        for (String entry : order) {
            if (node.equals(entry)) {
                total++;
            }
        }
        return total;
    }
}
