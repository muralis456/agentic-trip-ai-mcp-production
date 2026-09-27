package com.example.travel.agent;

import com.example.travel.graph.TravelState;
import com.example.travel.model.FlightOption;
import com.example.travel.model.Itinerary;
import com.example.travel.model.ItineraryDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ValidatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(ValidatorAgentService.class);

    public List<String> validate(TravelState state) {
        List<String> errors = new ArrayList<>();
        if (TravelState.isBlank(state.destination())) {
            errors.add("Destination is missing.");
        }
        if (state.departureDate().isAfter(state.returnDate())) {
            errors.add("Departure is after return.");
        }

        boolean flightsUnavailable = state.flights().isEmpty() || state.flights().stream()
                .allMatch(flight -> "unavailable".equalsIgnoreCase(flight.getStatus()));
        if (state.needsFlights() && !flightsUnavailable && !TravelState.isBlank(state.originIata())
                && !TravelState.isBlank(state.destinationIata())) {
            boolean routeMatch = state.flights().stream().anyMatch(flight ->
                    matchesAirport(flight, state.originIata(), state.destinationIata()));
            if (!routeMatch) {
                errors.add("No returned flight matches " + state.originIata() + " -> " + state.destinationIata() + ".");
            }
        }

        if (state.budgetSummary() != null && !state.budgetSummary().isWithinBudget()) {
            // Budget already drove replan; keep as a caveat after retries are exhausted.
            if (state.retryCount() >= state.maxRetries()) {
                errors.add(state.budgetSummary().getAssessment());
            }
        }

        long nights = state.nights();
        Itinerary itinerary = state.itinerary();
        if (state.needsItinerary()) {
            if (itinerary == null || itinerary.isEmpty()) {
                errors.add("Itinerary is empty.");
            } else if (itinerary.getDays() != null && !itinerary.getDays().isEmpty()) {
            int dayCount = itinerary.getDays().size();
            long expectedDays = nights + 1;
            if (Math.abs(dayCount - nights) > 2 && Math.abs(dayCount - expectedDays) > 2) {
                errors.add("Itinerary days (" + dayCount + ") do not match trip length of " + nights + " night(s).");
            }
            ItineraryDay first = itinerary.getDays().get(0);
            if (!mentions(first, "arrival", "arrive", "land", "landing", "check-in", "check in", "reach", "inbound", "day 1")) {
                errors.add("Day 1 should cover arrival.");
            }
            if (itinerary.getDays().size() >= 2) {
                ItineraryDay last = itinerary.getDays().get(itinerary.getDays().size() - 1);
                if (!mentions(last, "depart", "departure", "return", "fly home", "checkout", "check-out",
                        "fly back", "leave", "airport", "outbound", "last day")) {
                    errors.add("Last day should cover departure.");
                }
            }
            }
        }
        log.info("Validator found {} issue(s); retryCount={}", errors.size(), state.retryCount());
        return errors;
    }

    private boolean matchesAirport(FlightOption flight, String originIata, String destinationIata) {
        return originIata.equalsIgnoreCase(nullToEmpty(flight.getOrigin()))
                && destinationIata.equalsIgnoreCase(nullToEmpty(flight.getDestination()));
    }

    private boolean mentions(ItineraryDay day, String... keywords) {
        String text = ((day.getTitle() == null ? "" : day.getTitle()) + " " + day.activitiesText())
                .toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
