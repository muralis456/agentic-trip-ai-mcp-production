package com.example.travel.graph;

import com.example.travel.agent.*;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.model.*;
import com.example.travel.tool.AirportLookupTool;
import com.example.travel.graph.node.RagNode;
import com.example.travel.graph.node.HistoryNode;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.McpFlightSearchClient;
import com.example.travel.support.ToolFailureClassifier;
import com.example.travel.tool.ToolInvocationContext;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Executes the current AgentPlan. It is deliberately not a router: dependencies
 * come from the plan, successful work is retained, and only ready work is executed.
 */
@Component
public class ProductionExecutionNode implements NodeAction<TravelState> {
    private final FlightAgentService flight;
    private final HotelAgentService hotel;
    private final TravelResearchAgentService research;
    private final WeatherAgentService weather;
    private final BudgetAgentService budget;
    private final ItineraryAgentService itinerary;
    private final RagNode ragNode;
    private final HistoryNode historyNode;
    private final AirportLookupTool airportLookupTool;
    private final ExecutorService executor;
    private final GraphProgressHub progressHub;
    private final AgentRunControlService runControlService;

    public ProductionExecutionNode(FlightAgentService flight, HotelAgentService hotel,
            TravelResearchAgentService research, WeatherAgentService weather,
            BudgetAgentService budget, ItineraryAgentService itinerary,
            RagNode ragNode, HistoryNode historyNode, AirportLookupTool airportLookupTool,
            @org.springframework.beans.factory.annotation.Qualifier("travelParallelExecutor") ExecutorService executor,
            GraphProgressHub progressHub, AgentRunControlService runControlService) {
        this.flight = flight; this.hotel = hotel; this.research = research; this.weather = weather;
        this.budget = budget; this.itinerary = itinerary; this.ragNode = ragNode; this.historyNode = historyNode;
        this.airportLookupTool = airportLookupTool; this.executor = executor; this.progressHub = progressHub;
        this.runControlService = runControlService;
    }

    @Override public Map<String,Object> apply(TravelState state) {
        AgentPlan plan = state.agentPlan();
        Map<String,Object> updates = new LinkedHashMap<>();

        // Execute exactly one dependency wave. The graph invokes this node again
        // after the wave is persisted, so downstream tasks see the newly produced
        // state instead of an in-memory snapshot that does not contain prior wave
        // outputs. This is the key reliability boundary for dependency-aware plans.
        List<AgentTask> ready = plan.getTasks().stream()
                .filter(t -> !t.terminal())
                .filter(t -> plan.ready(t.getId()))
                .toList();

        if (ready.isEmpty()) {
            updates.put(TravelState.AGENT_PLAN, plan);
            updates.putAll(TravelState.trace("execute", "blocked", "no ready tasks"));
            return updates;
        }

        ready.forEach(t -> {
            plan.markStarted(t.getId());
            emitTaskStart(state, t);
        });

        List<AgentTask> parallel = ready.stream()
                .filter(t -> List.of("flights","hotels","research","weather","knowledge","history").contains(t.getId()))
                .toList();
        List<AgentTask> sequential = ready.stream()
                .filter(t -> !List.of("flights","hotels","research","weather","knowledge","history").contains(t.getId()))
                .toList();

        List<Future<TaskResult>> futures = parallel.stream()
                .map(task -> executor.submit(() -> execute(task.getId(), state)))
                .toList();

        try {
            for (int i = 0; i < futures.size(); i++) {
                TaskResult result;
                while (true) {
                    if (isStopRequested(state)) {
                        futures.forEach(future -> future.cancel(true));
                        throw new GraphStopRequestedException();
                    }
                    try {
                        result = futures.get(i).get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException ignored) {
                        // Poll durable run control while specialist calls are in flight.
                    }
                }
                emitTaskComplete(state, parallel.get(i), result.success(), result.error());
                merge(updates, result.updates());
                mark(plan, parallel.get(i), result.success(), result.error());
            }
        } catch (InterruptedException ex) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new GraphStopRequestedException(ex);
        } catch (ExecutionException ex) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = ex.getCause();
            if (cause instanceof GraphStopRequestedException stopped) throw stopped;
            throw new RuntimeException("A parallel travel task failed unexpectedly", cause == null ? ex : cause);
        }

        // Non-parallel tasks are executed only when their dependencies were
        // already satisfied at the beginning of this wave. In the next graph
        // iteration they will observe persisted outputs from this wave.
        for (AgentTask task : sequential) {
            if (isStopRequested(state)) throw new GraphStopRequestedException();
            TaskResult r = execute(task.getId(), state);
            merge(updates, r.updates());
            mark(plan, task, r.success(), r.error());
            emitTaskComplete(state, task, r.success(), r.error());
        }

        updates.put(TravelState.AGENT_PLAN, plan);
        updates.putAll(TravelState.trace("execute", "ok",
                "wave=" + ready.stream().map(AgentTask::getId).toList()));
        return updates;
    }

    private boolean readyNow(AgentPlan p, AgentTask t) { return t.getDependsOn().stream().allMatch(d -> { AgentTask x=p.task(d); return x==null || x.getStatus()==AgentTask.Status.SUCCEEDED; }); }

    private TaskResult execute(String id, TravelState s) {
        // The execution pool is shared by parallel specialist tasks, so identity
        // must be installed per task and removed in finally. The policy engine
        // reads this context immediately before every MCP invocation.
        try (ToolInvocationContext.Scope ignored =
                     ToolInvocationContext.open(
                             s.userId(), roleForUser(s.userId()))) {
            try {
                Map<String,Object> u = new LinkedHashMap<>();
            switch(id) {
                case "flights" -> { var r=flight.search(s); u.put(TravelState.FLIGHTS,r.flights()); u.put(TravelState.ORIGIN_IATA,r.originIata()); u.put(TravelState.DESTINATION_IATA,r.destinationIata()); if(r.flights().isEmpty()) return new TaskResult(false,u,"No usable flight options found"); }
                case "hotels" -> { var r=hotel.search(s); u.put(TravelState.HOTELS,r.hotels()); u.put(TravelState.HOTEL_FALLBACK_EXHAUSTED,r.fallbackExhausted()); if(r.hotels().isEmpty()) return new TaskResult(false,u,"No usable hotel options found"); }
                case "research" -> {
                    var r=research.research(s);
                    u.put(TravelState.RESEARCH,r.extraction().getResearch());
                    u.put(TravelState.ATTRACTIONS,r.extraction().getAttractions());
                    if ((r.extraction().getResearch() == null || r.extraction().getResearch().isEmpty())
                            && (r.extraction().getAttractions() == null || r.extraction().getAttractions().isEmpty())) {
                        return new TaskResult(false,u,"Research produced no usable evidence");
                    }
                }
                case "weather" -> {
                    var result = weather.forecast(s);
                    u.put(TravelState.WEATHER,result);
                    boolean usable = result != null && (!TravelState.isBlank(result.getSummary())
                            || (result.getDays() != null && !result.getDays().isEmpty()));
                    if (!usable) return new TaskResult(false,u,"Weather produced no usable forecast");
                }
                case "budget" -> {
                    var result = budget.assess(s);
                    u.put(TravelState.BUDGET_SUMMARY,result);
                    if (result == null || result.getEstimatedCost() == null || result.getEstimatedCost().signum() <= 0) {
                        return new TaskResult(false,u,"Budget produced no usable estimate");
                    }
                }
                case "itinerary" -> {
                    var result = itinerary.build(s);
                    u.put(TravelState.ITINERARY,result);
                    if (result == null || result.isEmpty()) return new TaskResult(false,u,"Itinerary produced no usable plan");
                }
                case "knowledge" -> {
                    u.putAll(ragNode.apply(s));
                    Object context = u.get(TravelState.RAG_CONTEXT);
                    if (context == null || String.valueOf(context).isBlank()) return new TaskResult(false,u,"Knowledge retrieval produced no usable context");
                }
                case "history" -> {
                    u.putAll(historyNode.apply(s));
                }
                default -> { return new TaskResult(false,u,"Unsupported task: "+id); }
            }
                return new TaskResult(true,u,"");
            } catch (Exception e) {
            if (e instanceof GraphStopRequestedException stopped) throw stopped;
            if (Thread.currentThread().isInterrupted() || isInterrupted(e)) {
                throw new GraphStopRequestedException(e);
            }
            Map<String,Object> failureUpdates = new LinkedHashMap<>();
            boolean retryable = e instanceof McpFlightSearchClient.FlightProviderException providerException
                    ? providerException.retryable()
                    : ToolFailureClassifier.fromException(e).isRetryable();
            failureUpdates.putAll(NodeFailureSupport.record(id, s, e, retryable,
                    s.nodeFailure().getNodeRetryCount()));
                return new TaskResult(false, failureUpdates,
                        e.getMessage() == null ? "execution failed" : e.getMessage());
            }
        }
    }

    private String roleForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            // Unit tests and internal graph invocations may not carry an HTTP
            // authentication context. They must still never receive an elevated role.
            return "USER";
        }
        return "USER";
    }

    private boolean isInterrupted(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return true;
        }
        return false;
    }

    private boolean isStopRequested(TravelState state) {
        String threadId = state.graphThreadId();
        return threadId != null && !threadId.isBlank()
                && runControlService.isStopRequested(threadId);
    }


    private void emitTaskStart(TravelState state, AgentTask task) {
        String threadId = state.graphThreadId();
        if (threadId == null || threadId.isBlank()) return;
        progressHub.emit(threadId, "task_start", Map.of(
                "task", task.getId(),
                "agent", task.getAgent(),
                "status", "RUNNING",
                "message", taskMessage(task.getId())));
    }

    private void emitTaskComplete(TravelState state, AgentTask task, boolean success, String error) {
        String threadId = state.graphThreadId();
        if (threadId == null || threadId.isBlank()) return;
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("task", task.getId());
        payload.put("agent", task.getAgent());
        payload.put("status", success ? "SUCCEEDED" : "FAILED");
        payload.put("message", success ? taskMessage(task.getId()) + " complete" : taskMessage(task.getId()) + " failed");
        if (!success && error != null && !error.isBlank()) payload.put("reason", error);
        progressHub.emit(threadId, "task_complete", payload);
    }

    private String taskMessage(String id) {
        return switch (id) {
            case "flights" -> "Searching for flights";
            case "hotels" -> "Searching for hotels";
            case "research" -> "Researching destinations and attractions";
            case "weather" -> "Checking destination weather";
            case "knowledge" -> "Retrieving travel knowledge";
            case "history" -> "Loading travel history";
            case "budget" -> "Calculating trip budget";
            case "itinerary" -> "Building your itinerary";
            default -> "Running " + id;
        };
    }

    private TravelState stateWith(Map<String,Object> updates, TravelState state) {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        // StateGraph does not expose a public immutable-copy constructor; downstream
        // services only need the already persisted state for normal graph execution.
        return state;
    }
    private void merge(Map<String,Object> target, Map<String,Object> source) { if(source!=null) target.putAll(source); }
    private void mark(AgentPlan p, AgentTask t, boolean ok, String error) {
        // Attempts are counted exactly once by AgentPlan.markStarted().
        if (ok) {
            t.setStatus(AgentTask.Status.SUCCEEDED);
            t.setFailureReason("");
        } else {
            t.setStatus(AgentTask.Status.FAILED);
            t.setFailureReason(error);
        }
    }
    private record TaskResult(boolean success, Map<String,Object> updates, String error) {}
}
