package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.model.Itinerary;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.ItinerarySupport;
import com.example.travel.support.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;

@Service
public class ItineraryAgentService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryAgentService.class);

    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;
    private final ObjectProvider<com.example.travel.service.McpItineraryClient> mcpItineraryClientProvider;
    private final String itineraryProvider;

    public ItineraryAgentService(RoutedLlm routedLlm, JsonSupport jsonSupport,
                                 ObjectProvider<com.example.travel.service.McpItineraryClient> mcpItineraryClientProvider,
                                 @Value("${travel.itinerary.provider:jettova}") String itineraryProvider) {
        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
        this.mcpItineraryClientProvider = mcpItineraryClientProvider;
        this.itineraryProvider = itineraryProvider == null ? "jettova" : itineraryProvider;
    }

    public Itinerary build(TravelState state) {
        long nights = state.nights();
        int expectedDays = ItinerarySupport.expectedDays(nights);
        log.info("Itinerary agent building plan for destination={} nights={} expectedDays={}",
                state.destination(), nights, expectedDays);

        LocalDate departure = state.departureDate();
        LocalDate returning = state.returnDate();

        if ("jettova".equalsIgnoreCase(itineraryProvider) || "auto".equalsIgnoreCase(itineraryProvider)) {
            try {
                var client = mcpItineraryClientProvider.getIfAvailable();
                if (client != null) {
                    var requirements = state.tripRequirements();
                    Itinerary jettova = client.generate(
                            state.destination(), departure, returning, state.travelers(), state.travelStyle(),
                            requirements != null && requirements.isFoodExperiences(),
                            requirements != null && requirements.isLocalExperiences(),
                            requirements != null && requirements.isFamilyFriendly(),
                            state.budgetLabel());
                    Itinerary normalized = ItinerarySupport.normalize(jettova, nights, state.destination(), state.attractions());
                    ensureRequestedPreferenceCoverage(normalized, state);
                    log.info("Itinerary provider=Jettova generated {} day(s) destination={}",
                            normalized.getDays() == null ? 0 : normalized.getDays().size(), state.destination());
                    return normalized;
                }
            } catch (Exception exception) {
                    if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Jettova itinerary unavailable; falling back to local Ollama itinerary destination={} reason={}",
                        state.destination(), exception.getMessage());
            }
        }

        String content;
        try {
            content = routedLlm.complete(AgentRole.ITINERARY,
                    "You are the Itinerary Agent. Return JSON only with shape "
                            + "{\"summary\":\"\",\"days\":[{\"day\":1,\"title\":\"\","
                            + "\"summary\":\"short day summary\",\"estimatedCost\":\"\",\"currency\":\"\","
                            + "\"activities\":[{\"name\":\"\",\"type\":\"sightseeing\","
                            + "\"indoorOutdoor\":\"indoor|outdoor|mixed\","
                            + "\"familyFriendly\":true,\"foodExperience\":false,\"localExperience\":true,"
                            + "\"description\":\"short useful visitor description\","
                            + "\"location\":\"specific area or venue location\","
                            + "\"duration\":\"e.g. 2 hours\","
                            + "\"estimatedCost\":\"estimated amount or empty\","
                            + "\"currency\":\"JPY|USD|INR\","
                            + "\"bookingUrl\":\"booking URL or empty\","
                            + "\"imageUrl\":\"image URL or empty\"}]}]}. " + "CRITICAL: days MUST contain exactly " + expectedDays + " objects (day 1.." + expectedDays + "). "
                            + "Each activity must have structured fields — never append '(indoor activity)' to names. "
                            + "Day 1 MUST mention arrival/check-in. Day " + expectedDays + " MUST mention departure/checkout. "
                            + "Use at most 2 activities per day plus one short food/local item when requested. Keep every activity name under 12 words. "
                            + "If rain is likely, move outdoor activities to drier days. "
                            + "Use flights, hotels, attractions, research, weather, and budget from shared state. "
                            + "Do not invent flight numbers.",
                    stateSnapshot(state, departure, returning, expectedDays));
        } catch (Exception exception) {
            if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Itinerary LLM failed for destination={}", state.destination(), exception);
            return ItinerarySupport.skeleton(nights, state.destination(), state.attractions());
        }

        Itinerary parsed = jsonSupport.read(content, Itinerary.class)
                .filter(itinerary -> !itinerary.isEmpty())
                .orElseGet(() -> ItinerarySupport.skeleton(nights, state.destination(), state.attractions()));
        Itinerary normalized = ItinerarySupport.normalize(parsed, nights, state.destination(), state.attractions());
        ensureRequestedPreferenceCoverage(normalized, state);
        log.info("Itinerary normalized to {} day(s)",
                normalized.getDays() == null ? 0 : normalized.getDays().size());
        return normalized;
    }

    private void ensureRequestedPreferenceCoverage(Itinerary itinerary, TravelState state) {
        if (itinerary == null || itinerary.getDays() == null) return;
        var requirements = state.tripRequirements();
        if (requirements == null) return;

        boolean hasFood = itinerary.getDays().stream().flatMap(day -> day.getActivities().stream())
                .anyMatch(activity -> activity != null && activity.isFoodExperience());
        boolean hasLocal = itinerary.getDays().stream().flatMap(day -> day.getActivities().stream())
                .anyMatch(activity -> activity != null && activity.isLocalExperience());
        boolean hasFamily = itinerary.getDays().stream().flatMap(day -> day.getActivities().stream())
                .anyMatch(activity -> activity != null && activity.isFamilyFriendly());

        for (int i = 1; i < itinerary.getDays().size() - 1; i++) {
            var day = itinerary.getDays().get(i);
            if (requirements.isFoodExperiences() && !hasFood) {
                day.getActivities().add(new com.example.travel.model.ItineraryActivity(
                        "Local family-friendly food experience", "food", "indoor", true, true, true));
                hasFood = true;
            }
            if (requirements.isLocalExperiences() && !hasLocal) {
                day.getActivities().add(new com.example.travel.model.ItineraryActivity(
                        "Explore a local neighborhood", "culture", "mixed", true, false, true));
                hasLocal = true;
            }
            if (requirements.isFamilyFriendly() && !hasFamily) {
                day.getActivities().add(new com.example.travel.model.ItineraryActivity(
                        "Family-friendly local activity", "family", "mixed", true, false, true));
                hasFamily = true;
            }
            if ((hasFood || !requirements.isFoodExperiences())
                    && (hasLocal || !requirements.isLocalExperiences())
                    && (hasFamily || !requirements.isFamilyFriendly())) break;
        }
    }

    private String stateSnapshot(TravelState state, LocalDate departure, LocalDate returning, int expectedDays) {
        String budgetNotes = state.budgetSummary() == null ? "" : state.budgetSummary().toDisplay();
        return """
                Destination=%s
                Origin=%s
                Dates=%s to %s
                Required days=%s (nights=%s)
                Travelers=%s
                Style=%s
                Budget=%s
                Replan guidance=%s
                Weather=%s
                Flights=%s
                Hotels=%s
                Attractions=%s
                Research=%s
                Knowledge context=%s
                Knowledge sources=%s
                Budget summary=%s
                """.formatted(
                state.destination(),
                state.origin(),
                departure,
                returning,
                expectedDays,
                state.nights(),
                state.travelers(),
                state.travelStyle(),
                state.budgetLabel(),
                state.replanGuidance(),
                state.weather() == null ? "" : state.weather().toDisplay(),
                state.flights().stream().map(flight -> flight.toDisplay()).toList(),
                state.hotels().stream().map(hotel -> hotel.toDisplay()).toList(),
                state.attractions().stream().map(attraction -> attraction.toDisplay()).toList(),
                state.research().stream().map(item -> item.toDisplay()).toList(),
                state.ragContext(),
                state.ragSources(),
                budgetNotes
        );
    }
}
