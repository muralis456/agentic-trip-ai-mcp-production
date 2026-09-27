package com.example.travel.support;

import com.example.travel.model.IntentPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TravelIntentNormalizerTest {

    @Test
    void extractsToFromRouteWithoutSwallowingTheRequest() {
        String request = """
                I want to plan a 5-day trip to Dubai from Bangalore.
                Give me the best places to visit, their cultural significance,
                local travel tips, and recommended areas to stay.
                Also check the current weather in Dubai and suggest suitable activities.
                """;

        assertEquals("Dubai", TravelIntentNormalizer.deterministicDestination(request));
        assertEquals("Bengaluru", TravelIntentNormalizer.deterministicOrigin(request));
    }

    @Test
    void preservesOnlyRequestedCapabilitiesForMixedTrip() {
        String request = "Plan a 5-day trip to Dubai from Bangalore. " +
                "Give me the best places to visit, cultural significance, " +
                "local travel tips, areas to stay and current weather.";

        IntentPlan plan = TravelIntentNormalizer.normalize(request, new IntentPlan());

        assertTrue(plan.isNeedsResearch());
        assertTrue(plan.isNeedsKnowledge());
        assertTrue(plan.isNeedsHotels());
        assertTrue(plan.isNeedsWeather());
        assertTrue(plan.isNeedsItinerary());
        assertFalse(plan.isNeedsFlights());
        assertFalse(plan.isNeedsBudget());
        assertEquals(IntentPlan.TRIP_PLANNING, plan.getRequestType());
    }

    @Test
    void explicitFlightOnlyDoesNotActivateEverything() {
        IntentPlan plan = TravelIntentNormalizer.normalize(
                "Find flights from Bangalore to Dubai",
                new IntentPlan());

        assertTrue(plan.isNeedsFlights());
        assertFalse(plan.isNeedsHotels());
        assertFalse(plan.isNeedsResearch());
        assertFalse(plan.isNeedsWeather());
        assertFalse(plan.isNeedsBudget());
        assertFalse(plan.isNeedsItinerary());
    }

    @Test
    void cultureQuestionIsKnowledgeOnly() {
        IntentPlan plan = TravelIntentNormalizer.normalize(
                "What is the cultural significance of Dubai?",
                new IntentPlan());

        assertTrue(plan.isNeedsKnowledge());
        assertFalse(plan.isNeedsFlights());
        assertFalse(plan.isNeedsHotels());
        assertFalse(plan.isNeedsWeather());
        assertFalse(plan.isNeedsBudget());
    }
    @Test
    void weatherConditionsAreWeatherNotGenericKnowledge() {
        IntentPlan plan = TravelIntentNormalizer.normalize(
                "I want to know weather conditions in bangalore to travel",
                new IntentPlan());

        assertTrue(plan.isNeedsWeather());
        assertFalse(plan.isNeedsKnowledge());
        assertFalse(plan.isNeedsResearch());
        assertFalse(plan.isNeedsFlights());
        assertFalse(plan.isNeedsHotels());
        assertFalse(plan.isNeedsBudget());
        assertFalse(plan.isNeedsItinerary());
        assertEquals(IntentPlan.WEATHER, plan.getRequestType());
    }

    @Test
    void itineraryRequestIsNotPromotedToFullTrip() {
        var plan = TravelIntentNormalizer.normalize(
                "Create a 5 day itinerary for Tokyo",
                new com.example.travel.model.IntentPlan());
        assertEquals("ITINERARY", plan.getRequestType());
        assertTrue(plan.isNeedsItinerary());
        assertFalse(plan.isNeedsFlights());
        assertFalse(plan.isNeedsHotels());
        assertFalse(plan.isNeedsWeather());
        assertFalse(plan.isNeedsBudget());
    }

}
