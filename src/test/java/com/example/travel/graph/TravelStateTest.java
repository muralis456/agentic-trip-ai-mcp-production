package com.example.travel.graph;

import com.example.travel.dto.TravelRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelStateTest {

    @Test
    void unsetBudgetIsExposedAsNullCeiling() {
        TravelState state = new TravelState(Map.of(TravelState.BUDGET, TravelState.UNSET_BUDGET));
        assertEquals(null, state.budget());
        assertNotNull(TravelState.SCHEMA.get(TravelState.BUDGET));
        assertNotNull(TravelState.SCHEMA.get(TravelState.ITINERARY));
        assertNotNull(TravelState.SCHEMA.get(TravelState.WEATHER));
    }

    @Test
    void fromRequestMapsCoreTripSlots() {
        TravelRequest request = new TravelRequest();
        request.setUserId("u1");
        request.setDestination("Beijing");
        request.setDepartureCity("Mumbai");
        request.setDepartureDate("2026-09-15");
        request.setReturnDate("2026-09-20");
        request.setAdults(2);
        request.setChildren(1);
        request.setBudget("₹200000");
        request.setPrompt("Family trip from Mumbai to Beijing");

        Map<String, Object> input = TravelState.fromRequest(request, "previous context");
        TravelState state = new TravelState(input);

        assertEquals("u1", state.userId());
        assertEquals("Beijing", state.destination());
        assertEquals("Mumbai", state.origin());
        assertEquals(LocalDate.parse("2026-09-15"), state.departureDate());
        assertEquals(3, state.travelers());
        assertEquals(new BigDecimal("200000"), state.budget());
        assertEquals(new BigDecimal("200000"), TravelState.parseBudget("2 lakh"));
        assertEquals(new BigDecimal("200000"), TravelState.parseBudget("₹2L"));
        assertTrue(state.historyContext().contains("previous"));
    }
    @Test
    void composedMultiCapabilityTripRequiresHumanApprovalBoundary() {
        TravelState state = new TravelState(TravelState.fromRequest(new TravelRequest(), ""));
        state = new TravelState(merge(state, Map.of(TravelState.REQUEST_TYPE, "MULTI_CAPABILITY")));
        com.example.travel.model.AgentPlan plan = new com.example.travel.model.AgentPlan();
        plan.setGoal("MULTI_CAPABILITY");
        plan.setTasks(java.util.List.of(
                new com.example.travel.model.AgentTask("flights", "flight", true),
                new com.example.travel.model.AgentTask("hotels", "hotel", true),
                new com.example.travel.model.AgentTask("budget", "budget", true),
                new com.example.travel.model.AgentTask("itinerary", "itinerary", true, "flights", "hotels", "budget")));
        Map<String,Object> updates = new java.util.LinkedHashMap<>();
        updates.put(TravelState.AGENT_PLAN, plan);
        state = new TravelState(merge(state, updates));

        assertTrue(state.isTripPlanningWorkflow());
    }

    @Test
    void itineraryOnlyDoesNotRequireHumanApprovalBoundary() {
        TravelState state = new TravelState(TravelState.fromRequest(new TravelRequest(), ""));
        com.example.travel.model.AgentPlan plan = new com.example.travel.model.AgentPlan();
        plan.setGoal("ITINERARY");
        plan.setTasks(java.util.List.of(new com.example.travel.model.AgentTask("itinerary", "itinerary", true)));
        Map<String,Object> updates = new java.util.LinkedHashMap<>();
        updates.put(TravelState.REQUEST_TYPE, "MULTI_CAPABILITY");
        updates.put(TravelState.AGENT_PLAN, plan);
        state = new TravelState(merge(state, updates));

        org.junit.jupiter.api.Assertions.assertFalse(state.isTripPlanningWorkflow());
    }

    @Test
    void tripPlanningAlwaysRunsWeatherForCompletedDashboard() {
        com.example.travel.model.IntentPlan plan = com.example.travel.model.IntentPlan.fullTrip();
        plan.setNeedsWeather(false); // simulate an intent model omission
        java.util.Map<String, Object> updates = new java.util.LinkedHashMap<>();

        TravelState.applyIntentAndRun(updates, plan);

        assertTrue((Boolean) updates.get(TravelState.NEEDS_WEATHER));
        assertTrue((Boolean) updates.get(TravelState.RUN_WEATHER));
    }

    private static Map<String, Object> merge(TravelState base, Map<String, Object> updates) {
        Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put(TravelState.USER_REQUEST, base.userRequest());
        data.put(TravelState.USER_ID, base.userId());
        data.put(TravelState.ORIGIN, base.origin());
        data.put(TravelState.DESTINATION, base.destination());
        data.put(TravelState.DEPARTURE_DATE, base.departureDate());
        data.put(TravelState.RETURN_DATE, base.returnDate());
        data.put(TravelState.TRAVELERS, base.travelers());
        data.put(TravelState.DATES_FLEXIBLE, base.datesFlexible());
        data.put(TravelState.ROUND_TRIP, base.roundTrip());
        data.put(TravelState.BUDGET, TravelState.UNSET_BUDGET);
        data.put(TravelState.BUDGET_LABEL, "medium");
        data.put(TravelState.TRAVEL_STYLE, "balanced");
        data.put(TravelState.NEEDS_FLIGHTS, false);
        data.put(TravelState.NEEDS_HOTELS, false);
        data.put(TravelState.NEEDS_RESEARCH, false);
        data.put(TravelState.NEEDS_WEATHER, false);
        data.put(TravelState.NEEDS_BUDGET, false);
        data.put(TravelState.NEEDS_ITINERARY, false);
        data.put(TravelState.NEEDS_KNOWLEDGE, false);
        data.put(TravelState.RUN_FLIGHTS, false);
        data.put(TravelState.RUN_HOTELS, false);
        data.put(TravelState.RUN_RESEARCH, false);
        data.put(TravelState.RUN_WEATHER, false);
        data.put(TravelState.RUN_BUDGET, false);
        data.put(TravelState.RUN_ITINERARY, false);
        data.put(TravelState.WEATHER, new com.example.travel.model.WeatherForecast("", "", false));
        data.put(TravelState.BUDGET_SUMMARY, new com.example.travel.model.BudgetSummary());
        data.put(TravelState.ITINERARY, new com.example.travel.model.Itinerary());
        data.putAll(updates);
        return data;
    }

}
