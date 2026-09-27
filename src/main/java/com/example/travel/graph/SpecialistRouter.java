package com.example.travel.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Picks the next graph node so inactive specialists do no work. The LangGraph
 * topology is intentionally fixed; canonical task guards prevent inactive
 * branches from executing their specialist logic. Native LangGraph fan-out uses {@link TravelGraphNodes#FAN_OUT} with parallel
 * executors;
 * a single specialist bypasses fan-out entirely.
 */
public final class SpecialistRouter {

    private SpecialistRouter() {
    }

    public static String afterPlanner(TravelState state) {
        String next;
        String reason;
        if (state.retryCount() >= state.maxRetries()) {
            next = TravelGraphNodes.SUPERVISOR;
            reason = "maxRetriesReached";
        } else if (state.shouldExecuteTask("flights")) {
            next = TravelGraphNodes.AIRPORT;
            reason = "needsFlights";
        } else {
            next = specialistEntry(state);
            reason = TravelGraphNodes.FAN_OUT.equals(next) ? "parallelSpecialists" : "directSpecialist";
        }
        GraphExecutionLogger.stageDecision(TravelGraphNodes.ROUTER, state, next, reason);
        GraphExecutionLogger.route(TravelGraphNodes.ROUTER, next, state, reason);
        return next;
    }

    public static String afterAirport(TravelState state) {
        String next = specialistEntry(state);
        GraphExecutionLogger.stageDecision(TravelGraphNodes.AIRPORT, state, next,
                TravelGraphNodes.FAN_OUT.equals(next) ? "airportResolved" : "directSpecialist");
        GraphExecutionLogger.route(TravelGraphNodes.AIRPORT, next, state,
                TravelGraphNodes.FAN_OUT.equals(next) ? "airportResolved" : "directSpecialist");
        return next;
    }

    public static String afterSupervisor(TravelState state) {
        String next;
        String reason;
        if (TravelGraphNodes.ROUTE_RETRY.equalsIgnoreCase(state.supervisorDecision())
                && state.retryCount() < state.maxRetries()) {
            next = TravelGraphNodes.REPLAN;
            reason = "supervisorRetry";
        } else if (TravelGraphNodes.ROUTE_RETRY.equalsIgnoreCase(state.supervisorDecision())) {
            // This protects the graph edge even if a stale or faulty supervisor
            // decision asks for another retry after the retry budget is spent.
            next = nextAfterSupervisor(state);
            reason = "maxRetriesReached";
        } else {
            next = nextAfterSupervisor(state);
            reason = next == TravelGraphNodes.BUDGET ? "needsBudget"
                    : next == TravelGraphNodes.ITINERARY ? "needsItinerary" : "validateOnly";
        }
        GraphExecutionLogger.stageDecision(TravelGraphNodes.SUPERVISOR, state, next, reason);
        GraphExecutionLogger.route(TravelGraphNodes.SUPERVISOR, next, state, reason);
        return next;
    }

    private static String nextAfterSupervisor(TravelState state) {
        if (state.shouldExecuteTask("budget")) {
            return TravelGraphNodes.BUDGET;
        }
        if (state.shouldExecuteTask("itinerary")) {
            return TravelGraphNodes.ITINERARY;
        }
        return TravelGraphNodes.VALIDATOR;
    }

    /**
     * When only one specialist is needed, route directly to it instead of
     * scheduling all four fan-out branches.
     */
    public static String specialistEntry(TravelState state) {
        List<String> specialists = plannedSpecialists(state);
        if (specialists.isEmpty()) {
            return TravelGraphNodes.SUPERVISOR;
        }
        if (specialists.size() == 1) {
            return specialists.get(0);
        }
        return TravelGraphNodes.FAN_OUT;
    }

    public static Optional<String> soleSpecialist(TravelState state) {
        List<String> specialists = plannedSpecialists(state);
        return specialists.size() == 1 ? Optional.of(specialists.get(0)) : Optional.empty();
    }

    public static boolean anySpecialist(TravelState state) {
        if (state != null && state.agentPlan() != null && !state.agentPlan().getTasks().isEmpty()) {
            return !state.agentPlan().preSupervisorTasks().isEmpty()
                    || state.runBudget()
                    || state.runItinerary();
        }
        return state.runFlights() || state.runHotels() || state.runResearch() || state.runWeather()
                || state.runBudget() || state.runItinerary();
    }

    public static List<String> plannedSpecialists(TravelState state) {
        // AgentPlan is the canonical orchestration contract. Legacy RUN_* flags
        // remain only as a compatibility projection for older checkpoints/tests.
        if (state != null && state.agentPlan() != null && !state.agentPlan().getTasks().isEmpty()) {
            List<String> planned = new ArrayList<>();
            state.agentPlan().preSupervisorTasks().forEach(task -> {
                switch (task.getId()) {
                    case "flights" -> planned.add(TravelGraphNodes.FLIGHT);
                    case "hotels" -> planned.add(TravelGraphNodes.HOTEL);
                    case "research" -> planned.add(TravelGraphNodes.RESEARCH);
                    case "weather" -> planned.add(TravelGraphNodes.WEATHER);
                    default -> { }
                }
            });
            if (!planned.isEmpty()) return planned;
            // An intentionally empty selective pass means "run nothing"; do
            // not fall back to stale RUN_* checkpoint flags.
            if (state.agentPlan().isSelectiveExecution()) return List.of();
        }

        List<String> nodes = new ArrayList<>();

        // These are the specialist nodes that execute BEFORE Supervisor.
        // Budget and Itinerary are downstream of Supervisor and must never
        // be selected as fan-out/entry specialists here.
        if (state.runFlights()) {
            nodes.add(TravelGraphNodes.FLIGHT);
        }
        if (state.runHotels()) {
            nodes.add(TravelGraphNodes.HOTEL);
        }
        if (state.runResearch()) {
            nodes.add(TravelGraphNodes.RESEARCH);
        }
        if (state.runWeather()) {
            nodes.add(TravelGraphNodes.WEATHER);
        }
        return nodes;
    }
}
