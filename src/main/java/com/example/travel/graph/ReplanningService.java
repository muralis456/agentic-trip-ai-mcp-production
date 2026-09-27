package com.example.travel.graph;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.model.PlanExtraction;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Creates a new plan from failure evidence instead of toggling RUN_* flags. */
@Service
public class ReplanningService {
    private final RoutedLlm llm;
    private final JsonSupport json;
    private final ProductionPlanningService planningService;

    public ReplanningService(RoutedLlm llm, JsonSupport json, ProductionPlanningService planningService) {
        this.llm = llm; this.json = json; this.planningService = planningService;
    }

    public AgentPlan replan(TravelState state, GoalEvaluation evaluation) {
        String system = "You are the Replanning Agent. The previous plan did not achieve the user's goal. "
                + "Create a NEW executable plan that changes strategy based on the failure. "
                + "Do not blindly repeat failed tasks unless the evidence indicates a changed input or legitimate retry. "
                + "Return the same PlanExtraction JSON contract. Keep already successful independent work out of the new plan. "
                + "Only use allowed ids: flights, hotels, research, weather, budget, itinerary, knowledge, history. "
                + "When a downstream result depends on a changed upstream result, include the downstream task too. "
                + "Return actions as zero or more typed tokens from this allow-list: cheaper_flight, reduce_hotel_budget, hotel_upgrade, remove_expensive_attractions, adjust_itinerary, get_weather_details, get_budget_breakdown, add_destination. "
                + "Actions must describe a concrete strategy change, not a generic retry.";
        String user = "ORIGINAL REQUEST: " + state.userRequest()
                + "\nGOAL: " + state.agentPlan().getGoal()
                + "\nSUCCESS CRITERIA: " + state.agentPlan().getSuccessCriteria()
                + "\nEVALUATION: status=" + evaluation.getStatus() + ", reason=" + evaluation.getReason()
                + ", unmet=" + evaluation.getUnmetCriteria() + ", blocking=" + evaluation.getBlockingIssues()
                + "\nCURRENT PLAN: " + state.agentPlan()
                + "\nCURRENT RESULTS: flights=" + state.flights().size() + ", hotels=" + state.hotels().size()
                + ", research=" + state.research().size() + ", weather=" + state.weather()
                + ", budget=" + state.budgetSummary() + ", itinerary=" + state.itinerary();
        try {
            PlanExtraction p = json.read(llm.complete(AgentRole.PLANNER, system, user), PlanExtraction.class).orElse(null);
            AgentPlan next = fromExtraction(p, state);
            if (!next.getTasks().isEmpty()) return next;
        } catch (Exception ignored) { }
        return deterministicRepair(state, evaluation);
    }

    private AgentPlan fromExtraction(PlanExtraction p, TravelState state) {
        if (p == null || p.getTasks() == null) return new AgentPlan();
        AgentPlan next = new AgentPlan();
        next.setGoal(state.agentPlan().getGoal());
        next.setVersion(state.agentPlan().getVersion() + 1);
        next.setSelectiveExecution(true);
        next.setStrategy(p.getStrategy());
        next.setPriority(p.getPriority());
        next.setActions(p.getActions());
        next.setSuccessCriteria(state.agentPlan().getSuccessCriteria());
        Set<String> allowed = "TRIP_PLANNING".equalsIgnoreCase(state.agentPlan().getGoal())
                ? new LinkedHashSet<>(List.of("flights","hotels","research","weather","budget","itinerary","knowledge"))
                : state.agentPlan().getTasks().stream().map(AgentTask::getId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> existing = state.agentPlan().getTasks().stream()
                .filter(t -> t.getStatus() == AgentTask.Status.SUCCEEDED)
                .map(AgentTask::getId).collect(java.util.stream.Collectors.toSet());
        for (PlanExtraction.PlannedTask raw : p.getTasks()) {
            String id = raw.getId() == null ? "" : raw.getId().toLowerCase();
            if (!com.example.travel.service.AgentCapabilityRegistry.known(id)) continue;
            if (!allowed.contains(id)) continue;
            if (existing.contains(id)) continue;
            AgentTask t = new AgentTask(id, id.equals("flights") ? "flight" : id.equals("hotels") ? "hotel" : id.equals("knowledge") ? "rag" : id,
                    raw.isRequired(), raw.getDependsOn().toArray(String[]::new));
            next.getTasks().add(t);
        }
        repairDependenciesAndMissingWork(next, state, evaluationFor(state));
        if (containsCycle(next)) return new AgentPlan();
        return next;
    }

    private GoalEvaluation evaluationFor(TravelState state) {
        return state.goalEvaluation();
    }

    private void repairDependenciesAndMissingWork(AgentPlan plan, TravelState state, GoalEvaluation evaluation) {
        Set<String> ids = plan.getTasks().stream().map(AgentTask::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean trip = "TRIP_PLANNING".equalsIgnoreCase(plan.getGoal());

        // Never lose an unsatisfied required task merely because the LLM omitted
        // it from its revised JSON. The evaluator's unmet contract is stronger
        // than a best-effort model response. Re-add failed/missing required work
        // when its evidence is still absent.
        for (AgentTask old : state.agentPlan().getTasks()) {
            if (!old.isRequired() || old.getStatus() == AgentTask.Status.SUCCEEDED) continue;
            if (hasEvidence(state, old.getId())) continue;
            if (isTerminalProviderFailure(state, old.getId())) continue;
            addTaskIfMissing(plan, ids, old.getId(), old.getAgent(), true);
        }

        // A replan is allowed to omit successful independent work, but it may not
        // omit evidence required by an unmet goal. Add only what is actually missing.
        if (needs(evaluation, "flight") && !state.hasUsableFlights()
                && !isTerminalProviderFailure(state, "flights")) {
            addTaskIfMissing(plan, ids, "flights", "flight", true);
        }
        if (needs(evaluation, "hotel") && !state.hasHotelResults()) addTaskIfMissing(plan, ids, "hotels", "hotel", true);
        if (needs(evaluation, "research") && state.research().isEmpty() && state.attractions().isEmpty()) addTaskIfMissing(plan, ids, "research", "research", true);
        if (needs(evaluation, "weather") && !hasWeather(state)) addTaskIfMissing(plan, ids, "weather", "weather", true);
        if (needs(evaluation, "budget") && !state.hasBudgetEstimate()) addTaskIfMissing(plan, ids, "budget", "budget", true);
        if (needs(evaluation, "itinerary") && (state.itinerary() == null || state.itinerary().isEmpty())) addTaskIfMissing(plan, ids, "itinerary", "itinerary", true);

        if (state.overBudget()) {
            // A budget replan must have at least one lever. Prefer the components
            // that are actually changeable and available for another search.
            if (!state.hotelCheaper()) addTaskIfMissing(plan, ids, "hotels", "hotel", true);
            else addTaskIfMissing(plan, ids, "hotels", "hotel", true);
            if (!plan.has("budget")) addTaskIfMissing(plan, ids, "budget", "budget", true);
            if (trip && !plan.has("itinerary")) addTaskIfMissing(plan, ids, "itinerary", "itinerary", true);
        }

        // Ensure only valid dependencies remain; successful dependencies may be
        // omitted because their persisted evidence is already in TravelState.
        for (AgentTask task : plan.getTasks()) {
            List<String> kept = task.getDependsOn().stream()
                    .filter(d -> d != null && !d.isBlank() && !d.equals(task.getId()))
                    .filter(ids::contains)
                    .distinct().toList();
            task.setDependsOn(new ArrayList<>(kept));
        }
        if (plan.has("budget")) {
            if (plan.has("flights") && !state.hasUsableFlights()) addDependency(plan.task("budget"), "flights");
            if (plan.has("hotels") && !state.hasHotelResults()) addDependency(plan.task("budget"), "hotels");
        }
        if (plan.has("itinerary")) {
            for (String dep : List.of("flights","hotels","research","weather","budget")) {
                if (plan.has(dep)) addDependency(plan.task("itinerary"), dep);
            }
        }
    }


    private boolean isTerminalProviderFailure(TravelState state, String taskId) {
        if (state == null || state.nodeFailure() == null || state.nodeFailure().isRetryable()) return false;
        String failed = state.nodeFailure().getLastFailedNode();
        if (failed == null || failed.isBlank()) return false;
        return taskId.equalsIgnoreCase(failed)
                || ("flights".equalsIgnoreCase(taskId) && TravelGraphNodes.FLIGHT.equalsIgnoreCase(failed));
    }

    private boolean hasEvidence(TravelState state, String id) {
        return switch (id) {
            case "flights" -> state.hasUsableFlights();
            case "hotels" -> state.hasHotelResults();
            case "research" -> !state.research().isEmpty() || !state.attractions().isEmpty();
            case "weather" -> hasWeather(state);
            case "budget" -> state.hasBudgetEstimate();
            case "itinerary" -> state.itinerary() != null && !state.itinerary().isEmpty();
            case "knowledge" -> !TravelState.isBlank(state.ragContext()) || !TravelState.isBlank(state.ragAnswer());
            case "history" -> !TravelState.isBlank(state.historyResult());
            default -> false;
        };
    }

    private boolean needs(GoalEvaluation e, String token) {
        return e != null && e.getUnmetCriteria().stream().anyMatch(v -> v != null && v.toLowerCase().contains(token));
    }

    private boolean hasWeather(TravelState state) {
        var w = state.weather();
        return w != null && (!TravelState.isBlank(w.getSummary()) || (w.getDays() != null && !w.getDays().isEmpty()));
    }

    private void addTaskIfMissing(AgentPlan plan, Set<String> ids, String id, String agent, boolean required) {
        if (ids.add(id)) plan.getTasks().add(new AgentTask(id, agent, required));
    }

    private void addDependency(AgentTask task, String dep) {
        if (task != null && !task.getDependsOn().contains(dep)) task.getDependsOn().add(dep);
    }

    private boolean containsCycle(AgentPlan plan) {
        java.util.Map<String,Integer> color = new java.util.HashMap<>();
        for (AgentTask t : plan.getTasks()) if (cycle(t.getId(), plan, color)) return true;
        return false;
    }

    private boolean cycle(String id, AgentPlan plan, java.util.Map<String,Integer> color) {
        int c = color.getOrDefault(id, 0);
        if (c == 1) return true;
        if (c == 2) return false;
        color.put(id, 1);
        AgentTask t = plan.task(id);
        if (t != null) for (String dep : t.getDependsOn()) if (plan.has(dep) && cycle(dep, plan, color)) return true;
        color.put(id, 2);
        return false;
    }

    private AgentPlan deterministicRepair(TravelState state, GoalEvaluation e) {
        AgentPlan p = new AgentPlan(); p.setGoal(state.agentPlan().getGoal()); p.setVersion(state.agentPlan().getVersion()+1); p.setSelectiveExecution(true); p.setSuccessCriteria(state.agentPlan().getSuccessCriteria());
        p.setStrategy("deterministic_repair"); p.setPriority("recovery");
        Set<String> ids = new LinkedHashSet<>();
        for (String u : e.getUnmetCriteria()) {
            if (u.contains("flight")) ids.add("flights");
            if (u.contains("hotel")) ids.add("hotels");
            if (u.contains("budget")) ids.add("budget");
            if (u.contains("itinerary")) ids.add("itinerary");
            if (u.contains("weather")) ids.add("weather");
        }
        if (state.overBudget()) { ids.add("hotels"); ids.add("budget"); ids.add("itinerary"); }
        if (ids.isEmpty()) ids.add("itinerary");
        for (String id : ids) {
            List<String> deps = id.equals("budget") ? List.of("flights","hotels") : id.equals("itinerary") ? List.of("flights","hotels","research","weather","budget") : List.of();
            p.getTasks().add(new AgentTask(id, id.equals("flights") ? "flight" : id.equals("hotels") ? "hotel" : id, true, deps.toArray(String[]::new)));
        }
        return p;
    }
}
