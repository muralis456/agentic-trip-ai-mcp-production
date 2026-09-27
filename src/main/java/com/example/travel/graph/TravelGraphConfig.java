package com.example.travel.graph;

import com.example.travel.graph.node.CompleteNode;
import com.example.travel.graph.node.FinalizationNode;
import com.example.travel.graph.node.HitlNode;
import com.example.travel.graph.node.CancelNode;
import com.example.travel.graph.node.IntentNode;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.beans.factory.annotation.Value;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.exception.GraphStopRequestedException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Production orchestration graph.
 *
 * Intent -> Plan -> Execute -> Evaluate -> (Replan -> Execute)* -> Final/HITL.
 * The graph controls lifecycle/checkpointing; the LLM controls semantic decisions.
 */
@Configuration
public class TravelGraphConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService travelParallelExecutor(
            @Value("${travel.runtime.max-concurrent-specialists:12}") int maxSpecialists) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(Math.max(2, maxSpecialists), r -> {
            Thread t = new Thread(r);
            t.setName("travel-agent-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService travelPlanExecutor(
            @Value("${travel.runtime.max-concurrent-plans:12}") int maxPlans,
            @Value("${travel.runtime.plan-queue-capacity:40}") int queueCapacity) {
        AtomicInteger n = new AtomicInteger();
        int maximum = Math.max(1, maxPlans);
        return new ThreadPoolExecutor(
                maximum,
                maximum,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("travel-plan-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean public ObjectStreamStateSerializer<TravelState> travelStateSerializer(){return new ObjectStreamStateSerializer<>(TravelState::new);}
    @Bean public PostgresSaver travelCheckpointSaver(DataSource ds,ObjectStreamStateSerializer<TravelState> s) throws SQLException {
        return PostgresSaver.builder().datasource(ds).stateSerializer(s).createTables(true).build();
    }

    @Bean
    public StateGraph<TravelState> travelStateGraph(
            IntentNode intentNode,
            ProductionPlanNode planNode,
            ProductionExecutionNode executeNode,
            ProductionFinalizeExecutionNode finalizeExecutionNode,
            ProductionEvaluateNode evaluateNode,
            ProductionReplanNode replanNode,
            FinalizationNode finalNode,
            HitlNode hitlNode,
            CompleteNode completeNode,
            CancelNode cancelNode,
            ObjectStreamStateSerializer<TravelState> serializer,
            GraphProgressHub graphProgressHub,
            AgentRunControlService runControlService) throws GraphStateException {

        return new StateGraph<>(TravelState.SCHEMA,serializer)
                .addNode("intent", async("intent",intentNode, graphProgressHub, runControlService))
                .addNode("plan", async("plan",planNode, graphProgressHub, runControlService))
                .addNode("execute", async("execute",executeNode, graphProgressHub, runControlService))
                .addNode("evaluate", async("evaluate",evaluateNode, graphProgressHub, runControlService))
                .addNode("replan", async("replan",replanNode, graphProgressHub, runControlService))
                .addNode("final", async("final",finalNode, graphProgressHub, runControlService))
                .addNode("hitl", async("hitl",hitlNode, graphProgressHub, runControlService))
                .addNode("complete", async("complete",completeNode, graphProgressHub, runControlService))
                .addNode("cancel", async("cancel",cancelNode, graphProgressHub, runControlService))
                .addEdge(START,"intent")
                .addEdge("intent","plan")
                .addEdge("plan","execute")
                .addConditionalEdges("execute", edge_async(state -> {
                    var plan = state.agentPlan();
                    boolean pending = plan.getTasks().stream().anyMatch(t ->
                            t.getStatus() == com.example.travel.model.AgentTask.Status.PENDING
                            || t.getStatus() == com.example.travel.model.AgentTask.Status.READY);
                    boolean ready = plan.getTasks().stream().anyMatch(t ->
                            !t.terminal() && plan.ready(t.getId()));
                    // The value returned by a conditional-edge resolver is a
                    // *mapping key*, not the destination node id. Returning
                    // "execute" here used to make LangGraph4j look for an
                    // "execute" mapping key, while the mapping actually
                    // contained "CONTINUE" -> "execute".
                    return pending && ready ? "CONTINUE" : "EVALUATE";
                }), EdgeMappings.builder().to("execute","CONTINUE").to("evaluate","EVALUATE").build())
                .addConditionalEdges("evaluate",edge_async(state -> {
                    var e = state.goalEvaluation();
                    if (e == null) return "FINAL";
                    // Terminal specialist/provider failures are not planning failures.
                    // Do not send the graph back through replan for the same provider.
                    if (state.nodeFailure() != null
                            && !com.example.travel.graph.TravelState.isBlank(state.nodeFailure().getLastFailedNode())
                            && !state.nodeFailure().isRetryable()) {
                        return "FINAL";
                    }
                    if (e.getStatus() == com.example.travel.model.GoalEvaluation.Status.ACHIEVED
                            || e.getStatus() == com.example.travel.model.GoalEvaluation.Status.NEEDS_USER) {
                        return "FINAL";
                    }
                    if (e.isRecoverable() && state.retryCount() < state.maxRetries()) {
                        return "REPLAN";
                    }
                    return "FINAL";
                }), EdgeMappings.builder()
                        .to("final", "FINAL")
                        .to("replan", "REPLAN")
                        .build())
                .addEdge("replan","execute")
                .addConditionalEdges("final",edge_async(state -> state.awaitingApproval() ? "HITL" : "COMPLETE"),
                        EdgeMappings.builder().to("hitl","HITL").to("complete","COMPLETE").build())
                .addConditionalEdges("hitl",edge_async(state -> {
                    String d=state.hitlDecision()==null?"":state.hitlDecision().toLowerCase();
                    return "modify".equals(d)?"MODIFY":"reject".equals(d)?"REJECT":"APPROVE";
                }),EdgeMappings.builder().to("replan","MODIFY").to("cancel","REJECT").to("complete","APPROVE").build())
                .addEdge("complete",END)
                .addEdge("cancel",END);
    }

    @Bean public CompiledGraph<TravelState> travelCompiledGraph(StateGraph<TravelState> graph,PostgresSaver saver,
            @Value("${travel.graph.max-iterations:30}") int maxIterations) throws GraphStateException {
        return graph.compile(CompileConfig.builder().checkpointSaver(saver).recursionLimit(maxIterations)
                .interruptBefore("hitl").releaseThread(false).build());
    }

    @Bean public RunnableConfig travelRunnableConfig(){return RunnableConfig.builder().build();}

    private AsyncNodeAction<TravelState> async(String name, NodeAction<TravelState> node, GraphProgressHub progressHub, AgentRunControlService runControlService){
        return node_async(state->{
            long start=System.nanoTime();
            String threadId = state.graphThreadId();
            if (threadId != null && !threadId.isBlank()) {
                progressHub.emit(threadId, "node_start", Map.of(
                        "node", name,
                        "status", "RUNNING",
                        "message", humanNodeMessage(name)));
            }
            try {
                if (threadId != null && !threadId.isBlank() && runControlService.isStopRequested(threadId)) {
                    throw new GraphStopRequestedException();
                }
                Map<String,Object> result = node.apply(state);
                if (threadId != null && !threadId.isBlank() && runControlService.isStopRequested(threadId)) {
                    throw new GraphStopRequestedException();
                }
                if (threadId != null && !threadId.isBlank()) {
                    progressHub.emit(threadId, "node_complete", Map.of(
                            "node", name,
                            "status", "SUCCEEDED",
                            "message", humanNodeMessage(name) + " complete"));
                }
                return result;
            } catch(Exception e){
                boolean stopRequested = threadId != null && !threadId.isBlank()
                        && runControlService.isStopRequested(threadId);
                boolean interrupted = Thread.currentThread().isInterrupted();
                boolean stopped = e instanceof GraphStopRequestedException || stopRequested || interrupted;
                if (stopped) {
                    // A user stop is control flow, not an application failure.
                    // Clear the executor thread's interrupt flag before handing
                    // the control exception back to LangGraph so the pooled
                    // worker is reusable for a later Continue.
                    Thread.interrupted();
                    if (threadId != null && !threadId.isBlank()) {
                        progressHub.emit(threadId, "node_complete", Map.of(
                                "node", name,
                                "status", "STOPPED",
                                "message", "Stopped. Latest saved checkpoint is preserved."));
                    }
                    if (e instanceof GraphStopRequestedException stop) {
                        throw stop;
                    }
                    throw new GraphStopRequestedException(e);
                }
                if (threadId != null && !threadId.isBlank()) {
                    progressHub.emit(threadId, "node_complete", Map.of(
                            "node", name,
                            "status", "FAILED",
                            "message", humanNodeMessage(name) + " failed"));
                }
                throw new RuntimeException("Node "+name+" failed",e);
            } finally {
                GraphExecutionLogger.nodeComplete(name,state,(System.nanoTime()-start)/1_000_000L);
            }
        });
    }

    private String humanNodeMessage(String node) {
        return switch (node) {
            case "intent" -> "Understanding your travel request";
            case "plan" -> "Creating an execution plan";
            case "execute" -> "Executing the travel plan";
            case "evaluate" -> "Checking whether your goal was achieved";
            case "replan" -> "Adjusting the plan based on the results";
            case "final" -> "Preparing the final travel plan";
            case "hitl" -> "Waiting for your decision";
            case "complete" -> "Travel plan completed";
            case "cancel" -> "Cancelling the current plan";
            default -> node;
        };
    }
}
