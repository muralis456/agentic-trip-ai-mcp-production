package com.example.travel.service;

import com.example.travel.graph.TravelState;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single capability contract used by the planner validator. The LLM may choose
 * tasks, but it may only choose capabilities that the current semantic intent
 * exposed. This prevents accidental expansion of specialist requests.
 */
public final class AgentCapabilityRegistry {

    private static final Set<String> ALL = Set.of(
            "flights", "hotels", "research", "weather", "budget", "itinerary", "knowledge", "history");

    private AgentCapabilityRegistry() {
    }

    public static boolean known(String taskId) {
        return taskId != null && ALL.contains(taskId);
    }

    public static String agentFor(String taskId) {
        return switch (taskId) {
            case "flights" -> "flight";
            case "hotels" -> "hotel";
            case "research" -> "research";
            case "weather" -> "weather";
            case "budget" -> "budget";
            case "itinerary" -> "itinerary";
            case "knowledge" -> "rag";
            case "history" -> "history";
            default -> "";
        };
    }

    public static Set<String> requestedBy(TravelState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (state == null) return ids;
        if (state.needsFlights()) ids.add("flights");
        if (state.needsHotels()) ids.add("hotels");
        if (state.needsResearch()) ids.add("research");
        if (state.needsWeather()) ids.add("weather");
        if (state.needsBudget()) ids.add("budget");
        if (state.needsItinerary()) ids.add("itinerary");
        if (state.needsKnowledge()) ids.add("knowledge");
        if (state.needsHistory()) ids.add("history");
        return ids;
    }

    public static Set<String> allowedFor(TravelState state) {
        if (state != null && "TRIP_PLANNING".equalsIgnoreCase(state.requestType())) {
            return new LinkedHashSet<>(Set.of("flights", "hotels", "research", "weather", "budget", "itinerary", "knowledge"));
        }
        return requestedBy(state);
    }
}
