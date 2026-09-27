package com.example.travel.graph;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.PlanExtraction;
import com.example.travel.service.RoutedLlm;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.support.JsonSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts semantic intent into an executable dependency graph. The model decides
 * what work is needed; Java validates the graph before it is executed.
 */
@Service
public class ProductionPlanningService {
    private final RoutedLlm llm;
    private final JsonSupport json;
    private final GraphProgressHub progressHub;

    public ProductionPlanningService(RoutedLlm llm, JsonSupport json, GraphProgressHub progressHub) {
        this.llm = llm;
        this.json = json;
        this.progressHub = progressHub;
    }

    public AgentPlan createPlan(TravelState state) {
        emitActivity(state, "Analyzing which capabilities are required");
        String system = "You are the Travel Planning Agent. Create an executable plan for the CURRENT user request. "
                + "Return JSON only with goal, strategy, priority, actions, successCriteria, tasks. "
                + "Allowed agents: flight, hotel, research, weather, budget, itinerary, rag, history. "
                + "Allowed task ids: flights, hotels, research, weather, budget, itinerary, knowledge, history. "
                + "Use dependencies only when truly required. A standalone budget calculation has no mandatory specialist dependency. "
                + "A full trip requires flights, hotels, research, weather and budget before itinerary. A specialist request must not pull in unrelated capabilities. "
                + "Every success criterion must describe an observable user outcome, not an internal node execution. "
                + "Never invent dates, destinations, prices or user constraints.";
        String user = "CURRENT REQUEST: " + state.userRequest()
                + "\nIntent: " + state.requestType()
                + "\nOrigin: " + state.origin()
                + "\nDestination: " + state.destination()
                + "\nDeparture: " + state.departureDate()
                + "\nReturn: " + state.returnDate()
                + "\nTravelers: " + state.travelers()
                + "\nBudget: " + state.budgetLabel()
                + "\nStyle: " + state.travelStyle();

        PlanExtraction extracted;
        try {
            emitActivity(state, "Planning the required tasks and dependencies");
            extracted = json.read(llm.complete(AgentRole.PLANNER, system, user), PlanExtraction.class).orElse(null);
        } catch (Exception e) {
            extracted = null;
        }

        AgentPlan plan = new AgentPlan();
        emitActivity(state, "Validating the proposed execution plan");
        if (extracted == null || extracted.getTasks().isEmpty()) {
            return fallback(state);
        }
        plan.setGoal(first(extracted.getGoal(), state.requestType()));
        plan.setVersion(Math.max(1, state.agentPlan().getVersion() + 1));
        plan.setSelectiveExecution(false);
        plan.setStrategy(extracted.getStrategy());
        plan.setPriority(extracted.getPriority());
        plan.setActions(extracted.getActions());
        plan.setSuccessCriteria(extracted.getSuccessCriteria());
        plan.setTasks(sanitizeTasks(extracted.getTasks(), state));
        if (plan.getTasks().isEmpty()) return fallback(state);
        if (plan.getSuccessCriteria().isEmpty()) {
            plan.setSuccessCriteria(defaultCriteria(plan));
        }
        emitActivity(state, "Execution plan ready");
        return plan;
    }

    private void emitActivity(TravelState state, String message) {
        if (state == null || progressHub == null) return;
        String threadId = state.graphThreadId();
        if (threadId == null || threadId.isBlank()) return;
        progressHub.emit(threadId, "activity", Map.of(
                "phase", "plan",
                "status", "RUNNING",
                "message", message));
    }

    private List<AgentTask> sanitizeTasks(List<PlanExtraction.PlannedTask> raw, TravelState state) {
        Map<String, AgentTask> tasks = new LinkedHashMap<>();
        for (PlanExtraction.PlannedTask p : raw) {
            String id = p.getId() == null ? "" : p.getId().trim().toLowerCase();
            String agent = p.getAgent() == null ? "" : p.getAgent().trim().toLowerCase();
            if (!com.example.travel.service.AgentCapabilityRegistry.known(id)) continue;
            if (tasks.containsKey(id)) continue;
            if (id.equals("knowledge")) agent = "rag";
            if (id.equals("flights")) agent = "flight";
            if (id.equals("hotels")) agent = "hotel";
            tasks.put(id, new AgentTask(id, agent, p.isRequired(),
                    p.getDependsOn() == null ? new String[0] : p.getDependsOn().toArray(String[]::new)));
        }

        boolean completeTrip = "TRIP_PLANNING".equalsIgnoreCase(state.requestType());

        // The planner may choose supporting tasks only from the semantic capability
        // contract of this user turn. This prevents an LLM hallucination from turning
        // a weather-only or hotel-only request into a full trip.
        java.util.Set<String> requested = com.example.travel.service.AgentCapabilityRegistry.allowedFor(state);
        if (!"TRIP_PLANNING".equalsIgnoreCase(state.requestType())) {
            tasks.entrySet().removeIf(entry -> !requested.contains(entry.getKey()));
        }

        // Dependencies are a correctness contract. Never silently delete an
        // LLM dependency and then execute the dependent task with missing inputs.
        // For known domain relationships, repair the plan by adding the missing
        // prerequisite task instead.
        if (completeTrip && tasks.containsKey("budget")) {
            ensureTask(tasks, "flights", "flight", true);
            ensureTask(tasks, "hotels", "hotel", true);
            addDependency(tasks.get("budget"), "flights");
            addDependency(tasks.get("budget"), "hotels");
        }
        if (completeTrip && tasks.containsKey("itinerary")) {
            ensureTask(tasks, "flights", "flight", true);
            ensureTask(tasks, "hotels", "hotel", true);
            ensureTask(tasks, "research", "research", true);
            ensureTask(tasks, "weather", "weather", true);
            ensureTask(tasks, "budget", "budget", true);
            addDependencies(tasks.get("budget"), List.of("flights", "hotels"));
            addDependencies(tasks.get("itinerary"), List.of("flights", "hotels", "research", "weather", "budget"));
        }

        // Every declared dependency must refer to a task in the plan. Unknown
        // dependencies are a planner contract violation; reject the plan rather
        // than converting them into an implicit success.
        for (AgentTask task : tasks.values()) {
            List<String> deps = task.getDependsOn();
            java.util.Set<String> unique = new java.util.LinkedHashSet<>();
            for (String dep : deps) {
                if (dep == null || dep.isBlank() || dep.equals(task.getId()) || !tasks.containsKey(dep)) {
                    return List.of();
                }
                unique.add(dep);
            }
            task.setDependsOn(new ArrayList<>(unique));
        }

        List<AgentTask> sanitized = new ArrayList<>(tasks.values());
        if (containsCycle(sanitized)) return List.of();
        return sanitized;
    }

    private void ensureTask(Map<String, AgentTask> tasks, String id, String agent, boolean required) {
        tasks.computeIfAbsent(id, k -> new AgentTask(id, agent, required));
    }

    private void addDependency(AgentTask task, String dependency) {
        if (task != null && dependency != null && !task.getDependsOn().contains(dependency)) {
            task.getDependsOn().add(dependency);
        }
    }

    private void addDependencies(AgentTask task, List<String> dependencies) {
        if (dependencies != null) dependencies.forEach(d -> addDependency(task, d));
    }

    private boolean containsCycle(List<AgentTask> tasks) {
        Map<String, AgentTask> byId = new LinkedHashMap<>();
        tasks.forEach(t -> byId.put(t.getId(), t));
        Map<String, Integer> color = new LinkedHashMap<>();
        for (AgentTask t : tasks) {
            if (dfsCycle(t.getId(), byId, color)) return true;
        }
        return false;
    }

    private boolean dfsCycle(String id, Map<String, AgentTask> byId, Map<String, Integer> color) {
        int c = color.getOrDefault(id, 0);
        if (c == 1) return true;
        if (c == 2) return false;
        color.put(id, 1);
        AgentTask task = byId.get(id);
        if (task != null) {
            for (String dep : task.getDependsOn()) {
                if (byId.containsKey(dep) && dfsCycle(dep, byId, color)) return true;
            }
        }
        color.put(id, 2);
        return false;
    }

    private List<String> defaultCriteria(AgentPlan plan) {
        List<String> criteria = new ArrayList<>();
        for (AgentTask task : plan.getTasks()) {
            if (task.isRequired()) criteria.add(task.getId() + " outcome");
        }
        return criteria;
    }

    public AgentPlan fallback(TravelState state) {
        // Fallback must preserve the semantic scope of the current request.
        // Never turn an itinerary/hotel/weather-only request into a full trip.
        com.example.travel.model.IntentPlan intent = new com.example.travel.model.IntentPlan();
        intent.setRequestType(first(state.requestType(), "GENERAL"));
        intent.setNeedsFlights(state.needsFlights());
        intent.setNeedsHotels(state.needsHotels());
        intent.setNeedsResearch(state.needsResearch());
        intent.setNeedsWeather(state.needsWeather());
        intent.setNeedsBudget(state.needsBudget());
        intent.setNeedsItinerary(state.needsItinerary());
        intent.setNeedsKnowledge(state.needsKnowledge());
        intent.setNeedsHistory(state.needsHistory());
        AgentPlan p = AgentPlan.fromIntent(intent);
        p.setGoal(first(state.requestType(), "GENERAL"));
        return p;
    }

    private String first(String a, String b) { return a == null || a.isBlank() ? b : a; }
}
