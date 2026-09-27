package com.example.travel.graph;

import com.example.travel.dto.TravelRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

class TravelStateDatesTest {
    @Test
    void relativeTodayRequestUsesTodayInsteadOfDefaultFlexibleDate() {
        TravelRequest request = new TravelRequest();
        request.setPrompt("Any flights from BLR to NRT for today");
        Map<String, Object> input = TravelState.fromRequest(request, "");
        TravelState state = new TravelState(input);
        assertEquals(LocalDate.now(), state.departureDate());
        assertTrue(!state.datesFlexible());
    }

    @Test
    void relativeTomorrowRequestUsesTomorrow() {
        TravelRequest request = new TravelRequest();
        request.setPrompt("Flights from BLR to NRT tomorrow");
        TravelState state = new TravelState(TravelState.fromRequest(request, ""));
        assertEquals(LocalDate.now().plusDays(1), state.departureDate());
        assertTrue(!state.datesFlexible());
    }

    @Test
    void durationOnlyRequestKeepsDatesFlexible() {
        TravelRequest request = new TravelRequest();
        request.setPrompt("From Bengaluru to Tokyo for 7 days");
        Map<String, Object> input = TravelState.fromRequest(request, "");
        TravelState state = new TravelState(input);
        assertTrue(state.datesFlexible());
        assertTrue(state.roundTrip());
    }
}
