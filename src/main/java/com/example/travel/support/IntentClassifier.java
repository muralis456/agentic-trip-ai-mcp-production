package com.example.travel.support;

import com.example.travel.model.IntentPlan;

/**
 * Deterministic intent detection. Ambiguous cases get a low confidence so the
 * Intent agent can ask an LLM.
 */
public final class IntentClassifier {

    public static final double LLM_THRESHOLD = 0.75;

    private IntentClassifier() {
    }

    public static IntentPlan classify(String request) {
        String text = request == null ? "" : request.toLowerCase();
        boolean trip = containsAny(text, "plan a", "plan my", "trip", "itinerary", "vacation", "holiday",
                "family", "lakh", "days under", "nights");
        boolean flights = containsAny(text, "flight", "flights", "fly from", "airfare");
        boolean hotels = containsAny(text, "hotel", "hotels", "stay in", "accommodation", "where should i stay",
                "where to stay");
        boolean research = containsAny(text, "places to visit", "sightseeing", "attractions",
                "things to do", "what to see", "what should i see", "recommendations", "best places");
        boolean weather = containsAny(text, "weather", "forecast", "rain");
        boolean bookedFlight = containsAny(text, "already booked", "already have a flight", "already have flights",
                "flight is booked", "flights are booked");

        if (bookedFlight) {
            IntentPlan plan = new IntentPlan();
            plan.setRequestType("STAY_AND_EXPLORE");
            plan.setNeedsFlights(false);
            plan.setNeedsHotels(hotels || trip || !research);
            plan.setNeedsResearch(research || trip || containsAny(text, "things to do", "what to see"));
            plan.setNeedsWeather(true);
            plan.setNeedsBudget(containsAny(text, "budget", "lakh", "under"));
            plan.setNeedsItinerary(research || trip || containsAny(text, "things to do"));
            plan.setStrategy("hotels_research");
            plan.setPriority("hotels");
            plan.setConfidence(0.86);
            if (hotels) {
                plan.setNeedsHotels(true);
            }
            if (research) {
                plan.setNeedsResearch(true);
            }
            return plan;
        }
        if (!trip && weather && !flights && !hotels && !research) {
            return IntentPlan.weatherOnly();
        }
        if (!trip && flights && !hotels && !research) {
            return IntentPlan.flightsOnly();
        }
        if (!trip && hotels && research && !flights) {
            IntentPlan plan = new IntentPlan();
            plan.setRequestType("STAY_AND_EXPLORE");
            plan.setNeedsFlights(false);
            plan.setNeedsHotels(true);
            plan.setNeedsResearch(true);
            plan.setNeedsWeather(true);
            plan.setNeedsBudget(false);
            plan.setNeedsItinerary(true);
            plan.setStrategy("hotels_research");
            plan.setPriority("hotels");
            plan.setConfidence(0.72);
            return plan;
        }
        if (!trip && hotels && !flights) {
            return IntentPlan.hotelsOnly();
        }
        if (!trip && research && !flights && !hotels) {
            return IntentPlan.researchOnly();
        }
        if (trip) {
            return IntentPlan.fullTrip();
        }
        IntentPlan fallback = IntentPlan.fullTrip();
        fallback.setConfidence(0.55);
        return fallback;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
