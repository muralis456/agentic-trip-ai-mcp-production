package com.example.travel.graph;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies LangGraph HITL interrupt/resume against the real PostgreSQL database
 * without running the full travel agent pipeline.
 */
class HitlPostgresCheckpointIT {

    @Test
    void interruptBeforeHitlSurvivesReloadAndApproveResume() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:5432/travel_agent");
        ds.setUser(System.getenv().getOrDefault("DB_USERNAME", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));

        ObjectStreamStateSerializer<TravelState> serializer = new ObjectStreamStateSerializer<>(TravelState::new);
        PostgresSaver saver = PostgresSaver.builder()
                .datasource(ds)
                .stateSerializer(serializer)
                .createTables(true)
                .build();

        StateGraph<TravelState> graph = new StateGraph<>(TravelState.SCHEMA, serializer)
                .addNode("draft", node_async(state -> Map.of(
                        TravelState.FINAL_TIPS, "draft-plan",
                        TravelState.AWAITING_APPROVAL, Boolean.TRUE)))
                .addNode(TravelGraphNodes.HITL, node_async(state -> TravelState.trace(
                        TravelGraphNodes.HITL, "ok", "decision=" + state.hitlDecision())))
                .addNode(TravelGraphNodes.COMPLETE, node_async(state -> Map.of(
                        TravelState.AWAITING_APPROVAL, Boolean.FALSE,
                        TravelState.HITL_DECISION, "approve")))
                .addEdge(START, "draft")
                .addEdge("draft", TravelGraphNodes.HITL)
                .addConditionalEdges(TravelGraphNodes.HITL,
                        edge_async(state -> "modify".equalsIgnoreCase(state.hitlDecision())
                                ? TravelGraphNodes.ROUTE_MODIFY : TravelGraphNodes.ROUTE_APPROVE),
                        EdgeMappings.builder()
                                .to(TravelGraphNodes.COMPLETE, TravelGraphNodes.ROUTE_APPROVE)
                                .to("draft", TravelGraphNodes.ROUTE_MODIFY)
                                .build())
                .addEdge(TravelGraphNodes.COMPLETE, END);

        CompiledGraph<TravelState> compiled = graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(TravelGraphNodes.HITL)
                .releaseThread(false)
                .build());

        String threadId = "hitl-it-" + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        compiled.invoke(Map.of(
                TravelState.USER_ID, "it-user",
                TravelState.DESTINATION, "Tokyo",
                TravelState.HITL_DECISION, ""), config);

        StateSnapshot<TravelState> paused = compiled.getState(config);
        assertEquals(TravelGraphNodes.HITL, paused.next());
        assertEquals("draft-plan", paused.state().finalTips());
        assertTrue(paused.state().awaitingApproval());

        // Simulate app restart: new saver (empty cache) + recompile against same DB.
        PostgresSaver reloadedSaver = PostgresSaver.builder()
                .datasource(ds)
                .stateSerializer(serializer)
                .createTables(true)
                .build();
        CompiledGraph<TravelState> reloaded = graph.compile(CompileConfig.builder()
                .checkpointSaver(reloadedSaver)
                .interruptBefore(TravelGraphNodes.HITL)
                .releaseThread(false)
                .build());

        StateSnapshot<TravelState> afterRestart = reloaded.getState(config);
        assertEquals(TravelGraphNodes.HITL, afterRestart.next());
        assertEquals("draft-plan", afterRestart.state().finalTips());

        TravelState approved = reloaded.invoke(GraphInput.resume(Map.of(
                TravelState.HITL_DECISION, "approve",
                TravelState.AWAITING_APPROVAL, Boolean.FALSE)), config)
                .orElseThrow();
        assertFalse(approved.awaitingApproval());
        assertEquals("approve", approved.hitlDecision());

        reloadedSaver.release(config);
    }

    @Test
    void interruptBeforeHitlRejectGoesToCancel() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:5432/travel_agent");
        ds.setUser(System.getenv().getOrDefault("DB_USERNAME", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));

        ObjectStreamStateSerializer<TravelState> serializer = new ObjectStreamStateSerializer<>(TravelState::new);
        PostgresSaver saver = PostgresSaver.builder()
                .datasource(ds)
                .stateSerializer(serializer)
                .createTables(true)
                .build();

        StateGraph<TravelState> graph = new StateGraph<>(TravelState.SCHEMA, serializer)
                .addNode("draft", node_async(state -> Map.of(
                        TravelState.FINAL_TIPS, "draft-plan",
                        TravelState.AWAITING_APPROVAL, Boolean.TRUE)))
                .addNode(TravelGraphNodes.HITL, node_async(state -> TravelState.trace(
                        TravelGraphNodes.HITL, "ok", "decision=" + state.hitlDecision())))
                .addNode(TravelGraphNodes.COMPLETE, node_async(state -> Map.of(
                        TravelState.AWAITING_APPROVAL, Boolean.FALSE,
                        TravelState.HITL_DECISION, "approve")))
                .addNode(TravelGraphNodes.CANCEL, node_async(state -> Map.of(
                        TravelState.AWAITING_APPROVAL, Boolean.FALSE,
                        TravelState.HITL_DECISION, "reject",
                        TravelState.FINAL_TIPS, "rejected")))
                .addEdge(START, "draft")
                .addEdge("draft", TravelGraphNodes.HITL)
                .addConditionalEdges(TravelGraphNodes.HITL,
                        edge_async(state -> {
                            String decision = state.hitlDecision() == null ? "" : state.hitlDecision().toLowerCase();
                            if ("modify".equals(decision)) {
                                return TravelGraphNodes.ROUTE_MODIFY;
                            }
                            if ("reject".equals(decision)) {
                                return TravelGraphNodes.ROUTE_REJECT;
                            }
                            return TravelGraphNodes.ROUTE_APPROVE;
                        }),
                        EdgeMappings.builder()
                                .to(TravelGraphNodes.COMPLETE, TravelGraphNodes.ROUTE_APPROVE)
                                .to("draft", TravelGraphNodes.ROUTE_MODIFY)
                                .to(TravelGraphNodes.CANCEL, TravelGraphNodes.ROUTE_REJECT)
                                .build())
                .addEdge(TravelGraphNodes.COMPLETE, END)
                .addEdge(TravelGraphNodes.CANCEL, END);

        CompiledGraph<TravelState> compiled = graph.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(TravelGraphNodes.HITL)
                .releaseThread(false)
                .build());

        String threadId = "hitl-reject-it-" + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        compiled.invoke(Map.of(
                TravelState.USER_ID, "it-user",
                TravelState.DESTINATION, "Tokyo",
                TravelState.HITL_DECISION, ""), config);

        assertEquals(TravelGraphNodes.HITL, compiled.getState(config).next());

        TravelState rejected = compiled.invoke(GraphInput.resume(Map.of(
                TravelState.HITL_DECISION, "reject",
                TravelState.AWAITING_APPROVAL, Boolean.FALSE)), config)
                .orElseThrow();
        assertEquals("reject", rejected.hitlDecision());
        assertEquals("rejected", rejected.finalTips());

        saver.release(config);
    }
}
