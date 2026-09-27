package com.example.travel.service;

import com.example.travel.dto.TripPlanResult;
import com.example.travel.graph.TravelState;
import com.example.travel.model.BudgetLineItem;
import com.example.travel.model.BudgetSummary;
import com.example.travel.model.WeatherForecast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TripPlanAssemblerScopeTest {

    @Test
    void weatherOnlyResponseDoesNotReplayAccumulatedBudget() {
        TravelState state = new TravelState(TravelState.fromRequest(
                new com.example.travel.dto.TravelRequest(), ""));

        Map<String, Object> updates = new java.util.LinkedHashMap<>();
        updates.put(TravelState.REQUEST_TYPE, "WEATHER");
        updates.put(TravelState.NEEDS_WEATHER, true);
        updates.put(TravelState.NEEDS_BUDGET, true);
        updates.put(TravelState.RUN_WEATHER, true);
        updates.put(TravelState.RUN_BUDGET, false);
        updates.put(TravelState.BUDGET_SUMMARY, new BudgetSummary());
        updates.put(TravelState.WEATHER, new WeatherForecast("Bengaluru", "Rain likely", true));
        state = new TravelState(merge(state, updates));

        TripPlanResult result = new TripPlanAssembler(new RequirementEvaluator()).assemble(state, "COMPLETE", false);

        assertNotNull(result.getWeather());
        assertTrue(result.getWeather().getSummary().contains("Rain"));
        assertTrue(result.getBudget() == null || result.getBudget().getLineItems().isEmpty());
        assertTrue(result.getFlights().isEmpty());
        assertTrue(result.getHotels().isEmpty());
        assertTrue(result.getItinerary().isEmpty());
    }


    @Test
    void hotelSpecialistResponsePreservesVerifiedHotelResult() {
        TravelState state = new TravelState(TravelState.fromRequest(
                new com.example.travel.dto.TravelRequest(), ""));
        Map<String, Object> updates = new java.util.LinkedHashMap<>();
        updates.put(TravelState.REQUEST_TYPE, "HOTEL_SEARCH");
        updates.put(TravelState.RUN_HOTELS, true);
        updates.put(TravelState.NEEDS_HOTELS, true);
        com.example.travel.model.HotelOption hotel =
                new com.example.travel.model.HotelOption("The Leela Palace Bengaluru", "HAL Road", "₹10,000-₹15,000", "Business and leisure");
        updates.put(TravelState.HOTELS, java.util.List.of(hotel));
        state = new TravelState(merge(state, updates));

        TripPlanResult result = new TripPlanAssembler(new RequirementEvaluator())
                .assemble(state, "COMPLETE", false);

        assertEquals(1, result.getHotels().size());
        assertEquals("The Leela Palace Bengaluru", result.getHotels().getFirst().getName());
        assertTrue(result.getFlights().isEmpty());
        assertTrue(result.getBudget() == null || result.getBudget().getLineItems().isEmpty());
    }

    private static Map<String, Object> merge(TravelState base, Map<String, Object> updates) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
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
        data.put(TravelState.WEATHER, new WeatherForecast("", "", false));
        data.put(TravelState.BUDGET_SUMMARY, new BudgetSummary());
        data.put(TravelState.ITINERARY, new com.example.travel.model.Itinerary());
        data.putAll(updates);
        return data;
    }
}
