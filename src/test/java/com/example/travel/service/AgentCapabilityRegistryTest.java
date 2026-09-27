package com.example.travel.service;

import com.example.travel.graph.TravelState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class AgentCapabilityRegistryTest {
    @Test
    void specialistStateAllowsOnlyRequestedCapability() {
        LinkedHashMap<String,Object> input = new LinkedHashMap<>();
        input.put(TravelState.REQUEST_TYPE, "HOTEL_SEARCH");
        input.put(TravelState.NEEDS_HOTELS, true);
        input.put(TravelState.NEEDS_FLIGHTS, false);
        input.put(TravelState.NEEDS_WEATHER, false);
        input.put(TravelState.NEEDS_BUDGET, false);
        TravelState state = new TravelState(input);

        assertEquals(java.util.Set.of("hotels"), AgentCapabilityRegistry.allowedFor(state));
    }
}
