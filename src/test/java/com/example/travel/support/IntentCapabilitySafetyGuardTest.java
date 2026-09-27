package com.example.travel.support;

import com.example.travel.model.IntentPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentCapabilitySafetyGuardTest {

    @Test
    void precautionsRequestDoesNotBecomeWeatherOrItinerary() {
        IntentPlan plan = new IntentPlan();
        plan.setRequestType("MULTI_CAPABILITY");
        plan.setNeedsKnowledge(true);
        plan.setNeedsWeather(true);
        plan.setNeedsItinerary(true);
        IntentCapabilitySafetyGuard.apply(
                "give me when if want to travel bangalore what precautions i need to take care",
                plan);

        assertTrue(plan.isNeedsKnowledge());
        assertFalse(plan.isNeedsWeather());
        assertFalse(plan.isNeedsItinerary());
    }

    @Test
    void explicitWeatherRemainsWeather() {
        IntentPlan plan = new IntentPlan();
        plan.setRequestType("MULTI_CAPABILITY");
        plan.setNeedsWeather(true);
        IntentCapabilitySafetyGuard.apply("what is the weather in Bangalore today?", plan);
        assertTrue(plan.isNeedsWeather());
    }

    @Test
    void itineraryOnlyRemainsItinerary() {
        IntentPlan plan = new IntentPlan();
        plan.setRequestType("ITINERARY");
        plan.setNeedsItinerary(true);
        IntentCapabilitySafetyGuard.apply("create a 5 day itinerary for Tokyo", plan);
        assertTrue(plan.isNeedsItinerary());
    }

    @Test
    void fullTripIsNotStrippedBySafetyGuard() {
        IntentPlan plan = new IntentPlan();
        plan.setRequestType(IntentPlan.TRIP_PLANNING);
        plan.setNeedsWeather(true);
        plan.setNeedsItinerary(true);
        IntentCapabilitySafetyGuard.apply("from Bengaluru to Paris for 5 days under 150000", plan);
        assertTrue(plan.isNeedsWeather());
        assertTrue(plan.isNeedsItinerary());
    }

    @Test
    void knowledgeRequestDoesNotBecomeResearch() {
        IntentPlan plan = new IntentPlan();
        plan.setRequestType("MULTI_CAPABILITY");
        plan.setNeedsKnowledge(true);
        plan.setNeedsResearch(true);
        IntentCapabilitySafetyGuard.apply("what precautions should I take when travelling to Bengaluru?", plan);
        assertTrue(plan.isNeedsKnowledge());
        assertFalse(plan.isNeedsResearch());
    }
}
