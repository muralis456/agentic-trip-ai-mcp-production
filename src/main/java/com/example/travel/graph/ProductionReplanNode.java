package com.example.travel.graph;

import com.example.travel.model.AgentPlan;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.jev.JevReplanDecisionService;
import com.example.travel.agent.IntentAgentService;
import com.example.travel.model.AgentTask;
import com.example.travel.model.ReplanAction;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProductionReplanNode implements NodeAction<TravelState> {
    private final ReplanningService replanning;
    private final IntentAgentService intentAgent;
    private final ProductionPlanningService planning;
    private final Optional<JevReplanDecisionService> jevReplanDecision;
    public ProductionReplanNode(ReplanningService replanning, IntentAgentService intentAgent, ProductionPlanningService planning, Optional<JevReplanDecisionService> jevReplanDecision){this.replanning=replanning;this.intentAgent=intentAgent;this.planning=planning;this.jevReplanDecision=jevReplanDecision;}
    /** Backward-compatible constructor for existing tests. */
    public ProductionReplanNode(ReplanningService replanning, IntentAgentService intentAgent, ProductionPlanningService planning){
        this(replanning, intentAgent, planning, Optional.empty());
    }
    @Override public Map<String,Object> apply(TravelState state){
        GoalEvaluation e=state.goalEvaluation();
        AgentPlan next;
        String manualRetry = state.retryTask();
        if (manualRetry != null && !manualRetry.isBlank()) {
            next = state.agentPlan();
            if (next == null) {
                throw new IllegalArgumentException("Cannot recover without an executable plan");
            }

            // Manual recovery is a recovery of the whole failed set, not a
            // hard-coded specialist retry. The UI normally sends ALL_FAILED;
            // older callers may still send a single task id.
            List<String> requested = Arrays.stream(manualRetry.split(","))
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .toList();
            List<String> failed = next.getTasks().stream()
                    .filter(AgentTask::isRequired)
                    .filter(t -> t.getStatus() == AgentTask.Status.FAILED)
                    .map(AgentTask::getId)
                    .toList();
            List<String> selected = (requested.size() == 1
                    && !"ALL_FAILED".equalsIgnoreCase(requested.get(0))
                    && !"ALL".equalsIgnoreCase(requested.get(0)))
                    ? requested
                    : failed;
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("No failed required tasks are available for recovery");
            }
            for (String taskId : selected) {
                if (next.task(taskId) == null) {
                    throw new IllegalArgumentException("Cannot retry unknown task: " + taskId);
                }
                if (!next.task(taskId).isRequired()
                        || next.task(taskId).getStatus() != AgentTask.Status.FAILED) {
                    throw new IllegalArgumentException("Task is not currently failed: " + taskId);
                }
            }
            // Preserve successful work. Reopen every failed capability and
            // automatically reopen all downstream dependents through the
            // dependency-aware plan. If several failures exist, they are all
            // recovered in the same plan; dependency waves determine ordering.
            next.selectForExecution(selected);
            next.setSuccessCriteria(state.agentPlan().getSuccessCriteria());
        } else if ("modify".equalsIgnoreCase(state.hitlDecision())) {
            var intent=intentAgent.classifyLatestRequestPlan(state);
            next=AgentPlan.fromIntent(intent);
            next.setVersion(state.agentPlan().getVersion()+1);
            next.setSelectiveExecution(true);
            next.setSuccessCriteria(state.agentPlan().getSuccessCriteria());
            if (next.getSuccessCriteria().isEmpty()) next=planning.createPlan(state);
        } else {
            next=replanning.replan(state,e);
        }
        Map<String,Object> u=new LinkedHashMap<>();
        int count=state.retryCount()+1;
        u.put(TravelState.RETRY_COUNT,count); u.put(TravelState.AGENT_PLAN,next); u.put(TravelState.PLAN_VERSION,next.getVersion());
        u.put(TravelState.GOAL_EVALUATION, new com.example.travel.model.GoalEvaluation());
        u.put(TravelState.SUPERVISOR_DECISION,"REPLAN");
        u.put(TravelState.REPLAN_NOTES, manualRetry != null && !manualRetry.isBlank()
                ? "manual recovery: failed-task recovery=" + manualRetry
                : "replanned from goal failure: " + e.getReason());
        u.put(TravelState.RETRY_TASK, "");

        // Turn the LLM's typed strategy into concrete state changes consumed by
        // specialist agents. Without this bridge, a replan that says "cheaper"
        // would execute the exact same provider query again.
        List<String> actions = next.getActions() == null ? java.util.List.of() : next.getActions();
        if (jevReplanDecision.isPresent() && !actions.isEmpty()) {
            var d = jevReplanDecision.get().choose(state, e, actions);
            if (d.accepted() && !"NONE".equals(d.action()) && !"ASK_USER".equals(d.action())) {
                String token = switch (d.action()) {
                    case "FLIGHT" -> "cheaper_flight";
                    case "HOTEL" -> "reduce_hotel_budget";
                    case "BUDGET" -> "reduce_hotel_budget";
                    case "ITINERARY" -> "adjust_itinerary";
                    case "RESEARCH" -> "add_destination";
                    case "WEATHER" -> "get_weather_details";
                    default -> null;
                };
                if (token != null) { next.setActions(java.util.List.of(token)); actions = next.getActions(); }
            }
            u.put(TravelState.REPLAN_NOTES, u.get(TravelState.REPLAN_NOTES) + " decision=" + d.action());
        }
        for (String token : actions) {
            var action = ReplanAction.fromToken(token).orElse(null);
            if (action == null) continue;
            switch (action) {
                case CHEAPER_FLIGHT -> {
                    u.put(TravelState.FLIGHT_PREFERENCE, "cheapest");
                    u.put(TravelState.COST_FACTOR, state.costFactor().multiply(BigDecimal.valueOf(0.95)));
                }
                case REDUCE_HOTEL_BUDGET -> u.put(TravelState.HOTEL_CHEAPER, Boolean.TRUE);
                case HOTEL_UPGRADE -> { u.put(TravelState.HOTEL_CHEAPER, Boolean.FALSE); u.put(TravelState.TRAVEL_STYLE, "upscale"); }
                default -> { }
            }
        }
        if (next.has("hotels")) u.put(TravelState.HOTEL_FALLBACK_EXHAUSTED, Boolean.FALSE);
        u.putAll(TravelState.trace("replan","ok","version="+next.getVersion()+" attempt="+count+" tasks="+next.getTasks().stream().map(t->t.getId()).toList()));
        return u;
    }
}
