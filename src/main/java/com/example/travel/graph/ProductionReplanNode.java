package com.example.travel.graph;

import com.example.travel.model.AgentPlan;
import com.example.travel.model.GoalEvaluation;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProductionReplanNode implements NodeAction<TravelState> {
    private final ReplanningService replanning;
    private final com.example.travel.agent.IntentAgentService intentAgent;
    private final ProductionPlanningService planning;
    public ProductionReplanNode(ReplanningService replanning, com.example.travel.agent.IntentAgentService intentAgent, ProductionPlanningService planning){this.replanning=replanning;this.intentAgent=intentAgent;this.planning=planning;}
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
            java.util.List<String> requested = java.util.Arrays.stream(manualRetry.split(","))
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .toList();
            java.util.List<String> failed = next.getTasks().stream()
                    .filter(com.example.travel.model.AgentTask::isRequired)
                    .filter(t -> t.getStatus() == com.example.travel.model.AgentTask.Status.FAILED)
                    .map(com.example.travel.model.AgentTask::getId)
                    .toList();
            java.util.List<String> selected = (requested.size() == 1
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
                        || next.task(taskId).getStatus() != com.example.travel.model.AgentTask.Status.FAILED) {
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
        java.util.List<String> actions = next.getActions() == null ? java.util.List.of() : next.getActions();
        for (String token : actions) {
            var action = com.example.travel.model.ReplanAction.fromToken(token).orElse(null);
            if (action == null) continue;
            switch (action) {
                case CHEAPER_FLIGHT -> {
                    u.put(TravelState.FLIGHT_PREFERENCE, "cheapest");
                    u.put(TravelState.COST_FACTOR, state.costFactor().multiply(java.math.BigDecimal.valueOf(0.95)));
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
