package com.example.travel.support;

import com.example.travel.model.IntentPlan;

import java.util.Locale;

/**
 * Conservative post-LLM safety guard for capability selection.
 *
 * The semantic model remains responsible for understanding novel language.
 * This class only removes capabilities that are clearly over-inferred from
 * generic travel context. It never invents a capability.
 */
public final class IntentCapabilitySafetyGuard {
    private IntentCapabilitySafetyGuard() {}

    public static void apply(String request, IntentPlan plan) {
        if (plan == null || request == null) return;
        String text = request.toLowerCase(Locale.ROOT);

        boolean fullTrip = IntentPlan.TRIP_PLANNING.equalsIgnoreCase(plan.getRequestType());

        // A generic travel/advice request must not accidentally become a live
        // weather request. Weather is retained for a real weather objective.
        boolean weatherCue = containsAny(text,
                "weather", "forecast", "temperature", "rain", "snow",
                "precipitation", "humid", "humidity", "hot", "cold",
                "weather conditions", "weather-dependent");
        if (plan.isNeedsWeather() && !weatherCue && !fullTrip) {
            plan.setNeedsWeather(false);
        }

        // Generic travel language is not an itinerary. A schedule/planning
        // objective must be present, unless the semantic request type already
        // explicitly identifies an itinerary or full trip.
        boolean itineraryCue = containsAny(text,
                "itinerary", "day-by-day", "day by day", "daily plan",
                "daily itinerary", "trip schedule", "schedule my trip",
                "schedule the trip", "plan my trip", "plan a trip",
                "create a trip plan", "build a trip plan", "organize my trip",
                "organize the trip", "arrange my trip", "prepare my trip",
                "design my trip", "make a trip plan", "vacation plan",
                "holiday plan", "journey plan");
        boolean itineraryType = fullTrip
                || "ITINERARY".equalsIgnoreCase(plan.getRequestType())
                || "ITINERARY_CHANGE".equalsIgnoreCase(plan.getRequestType());
        if (plan.isNeedsItinerary() && !itineraryCue && !itineraryType) {
            plan.setNeedsItinerary(false);
        }

        // "Travel tips / precautions" are durable knowledge, not live destination
        // research. Keep research only when the request actually asks for research
        // outcomes such as attractions, activities, recommendations, etc.
        boolean researchCue = containsAny(text,
                "places to visit", "best places", "attractions", "sightseeing",
                "things to do", "what to see", "activities", "recommend",
                "recommendation", "tourist spots", "must see", "restaurants",
                "cafes", "things worth visiting");
        if (plan.isNeedsResearch() && plan.isNeedsKnowledge() && !researchCue && !fullTrip) {
            plan.setNeedsResearch(false);
        }
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
