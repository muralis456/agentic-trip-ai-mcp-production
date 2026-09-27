package com.example.travel.support;

import com.example.travel.model.IntentPlan;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic semantic safety/normalization layer around LLM intent output.
 *
 * The LLM is responsible for understanding natural language. This class owns
 * high-confidence guardrails: capability activation, trip-planning semantics,
 * and extraction of the destination/origin from common travel constructions.
 * It deliberately does not try to replace the LLM with a giant keyword list.
 */
public final class TravelIntentNormalizer {

    private static final Pattern FROM_TO = Pattern.compile(
            "(?i)\\bfrom\\s+(.+?)\\s+to\\s+([A-Za-z][A-Za-z .'-]*?)(?=\\s+(?:for|on|between|during|with|including|and)\\b|[,.!?]|$)");
    private static final Pattern TO_FROM = Pattern.compile(
            "(?i)\\bto\\s+([A-Za-z][A-Za-z .'-]*?)\\s+from\\s+([A-Za-z][A-Za-z .'-]*?)(?=[,.!?]|$)");
    private static final Pattern PLAN_TRIP = Pattern.compile(
            "(?i)\\b(?:plan|planning|organize|arrange|prepare|design|build|create|make)\\b.{0,40}\\b(?:trip|travel|holiday|vacation|journey)\\b");
    private static final Pattern DURATION = Pattern.compile(
            "(?i)\\b(\\d+)\\s*(?:day|days|night|nights)\\b");

    private TravelIntentNormalizer() {}

    public static IntentPlan normalize(String request, IntentPlan candidate) {
        String text = request == null ? "" : request.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        IntentPlan plan = candidate == null ? new IntentPlan() : candidate;

        // An itinerary is a specialist objective, not automatically a complete
        // trip. Only an explicit request to plan/organize the trip becomes the
        // full TRIP_PLANNING contract.
        boolean planning = PLAN_TRIP.matcher(text).find()
                || lower.contains("trip plan")
                // A complete travel contract can omit the word "plan".
                // Route + duration + a trip-wide budget is a high-confidence
                // request to organize a trip, not a flight-only lookup.
                || (hasRouteHint(text) && hasDurationHint(text) && budgetHint(lower));

        boolean flights = explicitFlight(lower);
        boolean hotels = explicitHotel(lower);
        boolean weather = explicitWeather(lower);
        boolean research = explicitResearch(lower);
        boolean knowledge = durableKnowledge(lower);

        // A request that combines live weather with a travel objective asks for
        // two different kinds of information: live conditions from the Weather
        // specialist and durable practical guidance (packing, rain planning,
        // local transport, etc.) from RAG. Keep both capabilities instead of
        // making weather and knowledge mutually exclusive.
        // Live weather is its own capability. Do not infer durable RAG
        // knowledge merely because the user says they are travelling.

        boolean budget = explicitBudget(lower);
        boolean itinerary = explicitItinerary(lower);

        // A multi-objective trip request must retain every clearly requested
        // capability; never collapse it to one specialist.
        if (planning) {
            itinerary = true;
            research = research || containsAny(lower,
                    "places to visit", "best places", "attractions", "sightseeing",
                    "things to do", "what to see", "activities", "recommend places");
            knowledge = knowledge || containsAny(lower,
                    "cultural", "culture", "significance", "travel tips", "local tips",
                    "travel advice", "tradition", "history", "visa", "safety");
            hotels = hotels || containsAny(lower,
                    "area to stay", "areas to stay", "where to stay", "stay area",
                    "recommended area", "recommended areas", "accommodation");
            weather = weather || containsAny(lower,
                    "current weather", "weather", "forecast", "temperature", "rain");
            plan.setRequestType(IntentPlan.TRIP_PLANNING);
        } else if (flights || hotels || weather || research || budget || itinerary || knowledge) {
            // For non-planning requests, explicit capabilities are authoritative.
            // Do not preserve an LLM label such as TRAVEL_INFORMATION when the
            // actual request explicitly asks for weather; the response renderer
            // uses requestType to select the live specialist result.
            plan.setRequestType(inferRequestType(flights, hotels, research, weather, budget, itinerary, knowledge));
        }

        // Explicit user requirements override an LLM omission. Existing state
        // values are never considered here.
        plan.setNeedsFlights(flights || (planning && containsAny(lower, "book flight", "find flight", "flight options", "airfare", "fly from")));
        plan.setNeedsHotels(hotels);
        plan.setNeedsResearch(research);
        plan.setNeedsWeather(weather);
        plan.setNeedsBudget(budget);
        plan.setNeedsItinerary(itinerary);
        plan.setNeedsKnowledge(knowledge);

        // Trip-planning is normalized into the complete dashboard contract by
        // AgentPlan.fromIntent(). Keep this classifier focused on semantics;
        // do not duplicate execution-policy expansion here.
        if (planning) {
            plan.setRequestType(IntentPlan.TRIP_PLANNING);
            plan.setStrategy("adaptive_execution");
            plan.setPriority("balanced");
            plan.setConfidence(Math.max(plan.getConfidence(), 0.88));
        }

        return plan;
    }

    private static boolean hasRouteHint(String text) {
        return FROM_TO.matcher(text == null ? "" : text).find()
                || TO_FROM.matcher(text == null ? "" : text).find();
    }

    private static boolean hasDurationHint(String text) {
        return DURATION.matcher(text == null ? "" : text).find();
    }

    private static boolean budgetHint(String lower) {
        if (lower == null) return false;
        return lower.matches(".*(?:under|below|within|budget|₹|rs\\.?|inr|usd|\\$|\\u20ac|\\u00a3)\\s*.*");
    }

    public static String deterministicOrigin(String request) {
        String text = request == null ? "" : request;
        // Prefer the unambiguous "to X from Y" construction.
        Matcher toFrom = TO_FROM.matcher(text);
        if (toFrom.find()) return normalizePlace(toFrom.group(2));
        Matcher m = FROM_TO.matcher(text);
        if (m.find()) return normalizePlace(m.group(1));
        return "";
    }

    public static String deterministicDestination(String request) {
        String text = request == null ? "" : request;
        // "to Dubai from Bangalore" is more reliable than searching for a
        // later "from ... to ..." phrase in the rest of the sentence.
        Matcher toFrom = TO_FROM.matcher(text);
        if (toFrom.find()) return normalizePlace(toFrom.group(1));
        Matcher fromTo = FROM_TO.matcher(text);
        if (fromTo.find()) return normalizePlace(fromTo.group(2));
        return "";
    }

    private static boolean explicitFlight(String t) {
        return containsAny(t, "flight", "flights", "airfare", "fly from", "fly to",
                "book a flight", "book flight", "find flight", "flight options");
    }

    private static boolean explicitHotel(String t) {
        return containsAny(t, "hotel", "hotels", "accommodation", "room", "rooms",
                "where to stay", "where should i stay", "area to stay", "areas to stay",
                "recommended area to stay", "recommended areas to stay", "stay in");
    }

    private static boolean explicitWeather(String t) {
        return containsAny(t, "weather", "forecast", "temperature", "rain", "snow",
                "hot will it be", "cold will it be", "weather conditions");
    }

    private static boolean explicitResearch(String t) {
        return containsAny(t, "places to visit", "best places", "attractions", "sightseeing",
                "things to do", "what to see", "activities", "recommendations",
                "recommend places", "tourist spots", "must see");
    }

    private static boolean durableKnowledge(String t) {
        return containsAny(t, "cultural", "culture", "cultural significance", "history",
                "historical", "tradition", "local travel tips", "travel tips",
                "local tips", "travel advice", "visa", "travel rules", "safety",
                "packing", "best time to visit", "when to visit", "why is",
                "what is", "tell me about", "information about", "travel guide",
                "destination information", "city information");
    }

    private static boolean explicitBudget(String t) {
        return containsAny(t, "budget", "cost", "costs", "expense", "expenses",
                "price", "prices", "affordable", "affordability", "cost breakdown",
                "how much will", "how much does");
    }

    private static boolean explicitItinerary(String t) {
        return containsAny(t, "itinerary", "day-by-day", "day by day", "trip schedule",
                "schedule my trip", "daily plan", "daily itinerary");
    }

    private static boolean durationMentioned(String t) {
        return DURATION.matcher(t).find();
    }

    private static boolean travelContext(String t) {
        return containsAny(t, "to travel", "for travel", "when travelling",
                "when traveling", "while travelling", "while traveling",
                "during travel", "for my trip", "on my trip", "travel trip");
    }

    private static String inferRequestType(boolean f, boolean h, boolean r, boolean w,
                                           boolean b, boolean i, boolean k) {
        int count = (f?1:0)+(h?1:0)+(r?1:0)+(w?1:0)+(b?1:0)+(i?1:0)+(k?1:0);
        if (count > 1) return "MULTI_CAPABILITY";
        if (f) return IntentPlan.FLIGHT_SEARCH;
        if (h) return IntentPlan.HOTEL_SEARCH;
        if (w) return IntentPlan.WEATHER;
        if (r) return IntentPlan.RESEARCH;
        if (k) return "KNOWLEDGE_QUERY";
        if (b) return "BUDGET";
        if (i) return "ITINERARY";
        return "GENERAL";
    }

    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    private static String normalizePlace(String value) {
        if (value == null) return "";
        String s = value.trim().replaceAll("[.!?,]+$", "").trim();
        if (s.equalsIgnoreCase("bangalore")) return "Bengaluru";
        if (s.equalsIgnoreCase("blr")) return "Bengaluru";
        if (s.equalsIgnoreCase("bengaluru")) return "Bengaluru";
        return s;
    }
}
