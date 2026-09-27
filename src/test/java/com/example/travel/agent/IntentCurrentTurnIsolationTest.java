package com.example.travel.agent;

import com.example.travel.model.IntentPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Regression contract: a new semantic turn must never inherit old capabilities. */
class IntentCurrentTurnIsolationTest {

    @Test
    void semanticCapabilityPlanIsIndependentOfPreviousTripCapabilities() {
        IntentPlan previous = IntentPlan.fullTrip();
        IntentPlan current = IntentPlan.weatherOnly();

        assertTrue(previous.isNeedsFlights());
        assertTrue(previous.isNeedsHotels());
        assertTrue(previous.isNeedsBudget());

        assertTrue(current.isNeedsWeather());
        assertFalse(current.isNeedsFlights());
        assertFalse(current.isNeedsHotels());
        assertFalse(current.isNeedsBudget());
        assertFalse(current.isNeedsItinerary());
    }
}
