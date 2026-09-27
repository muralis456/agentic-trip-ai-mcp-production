package com.example.travel.graph;

import com.example.travel.agent.PlannerAgentService;
import com.example.travel.model.AgentPlan;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProductionPlanNode implements NodeAction<TravelState> {
    private final PlannerAgentService slotPlanner;
    private final ProductionPlanningService planning;
    public ProductionPlanNode(PlannerAgentService slotPlanner, ProductionPlanningService planning) { this.slotPlanner=slotPlanner; this.planning=planning; }
    @Override public Map<String,Object> apply(TravelState state) {
        Map<String,Object> u=new LinkedHashMap<>(slotPlanner.plan(state));
        TravelState projected = state;
        AgentPlan plan=planning.createPlan(projected);
        String missing = missingRequiredInput(state, plan, u);
        if (!missing.isBlank()) {
            plan.getTasks().forEach(t -> {
                if (t.isRequired() && !t.terminal()) t.setStatus(com.example.travel.model.AgentTask.Status.SKIPPED);
            });
            u.put(TravelState.USER_INPUT_REQUIRED, Boolean.TRUE);
            u.put(TravelState.USER_INPUT_QUESTION, missing);
            u.put(TravelState.AWAITING_APPROVAL, Boolean.TRUE);
        } else {
            u.put(TravelState.USER_INPUT_REQUIRED, Boolean.FALSE);
            u.put(TravelState.USER_INPUT_QUESTION, "");
        }
        u.put(TravelState.AGENT_PLAN,plan); u.put(TravelState.PLAN_VERSION,plan.getVersion());
        u.put(TravelState.PLAN_STRATEGY,"llm_goal_plan"); u.put(TravelState.PLAN_PRIORITY,"goal");
        u.putAll(TravelState.trace("plan","ok","goal="+plan.getGoal()+" version="+plan.getVersion()+" tasks="+plan.getTasks().stream().map(t->t.getId()).toList()));
        return u;
    }
    private String missingRequiredInput(TravelState state, AgentPlan plan, Map<String,Object> updates) {
        boolean flight = plan.has("flights");
        boolean routeBased = flight || plan.has("hotels") || plan.has("research") || plan.has("weather") || plan.has("itinerary") || plan.has("budget");
        String effectiveDestination = String.valueOf(updates.getOrDefault(TravelState.DESTINATION, state.destination()));
        String effectiveOrigin = String.valueOf(updates.getOrDefault(TravelState.ORIGIN, state.origin()));
        if (routeBased && TravelState.isBlank(effectiveDestination)) {
            return "What destination should I use for this request?";
        }
        if (flight && TravelState.isBlank(effectiveOrigin)) {
            return "What city or airport are you flying from?";
        }
        if ("TRIP_PLANNING".equalsIgnoreCase(plan.getGoal()) && state.datesFlexible()
                && !state.userRequest().matches("(?i).*\\b\\d+\\s*(?:day|days|night|nights)\\b.*")) {
            return "What travel dates or trip duration should I use?";
        }
        if (plan.has("itinerary") && state.datesFlexible()
                && !state.userRequest().matches("(?i).*\\b\\d+\\s*(?:day|days|night|nights)\\b.*")) {
            return "How many days should the itinerary cover?";
        }
        return "";
    }

}

