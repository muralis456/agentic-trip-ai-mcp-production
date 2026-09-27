package com.example.travel.model;

import com.example.travel.graph.TravelState;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Typed replan commands.
 *
 * The LLM decides which action is appropriate.
 * This enum only defines the actions that the executor can perform.
 */
public enum ReplanAction {

    /**
     * User wants flight information for the current trip.
     */
    GET_FLIGHT_DETAILS("get_flight_details"),

    /**
     * User wants a cheaper flight.
     */
    CHEAPER_FLIGHT("cheaper_flight", "prefer_cheaper"),

    /**
     * Reduce hotel cost.
     */
    REDUCE_HOTEL_BUDGET("reduce_hotel_budget"),

    /**
     * Remove expensive attractions.
     */
    REMOVE_EXPENSIVE_ATTRACTIONS("remove_expensive_attractions"),

    /**
     * Upgrade the hotel.
     */
    HOTEL_UPGRADE("hotel_upgrade", "upgrade_hotel"),

    /**
     * User wants hotel information for the current trip.
     */
    GET_HOTEL_DETAILS("get_hotel_details"),

    /**
     * Change/rebuild the itinerary.
     */
    ADJUST_ITINERARY("adjust_itinerary"),

    GET_WEATHER_DETAILS("get_weather_details"),
    GET_BUDGET_BREAKDOWN("get_budget_breakdown"),

    /**
     * Add another destination.
     */
    ADD_DESTINATION("add_destination");

    private final List<String> aliases;

    ReplanAction(String... aliases) {
        this.aliases = Arrays.asList(aliases);
    }

    public static Optional<ReplanAction> fromToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String normalized = token.trim().toLowerCase(Locale.ROOT);

        for (ReplanAction action : values()) {
            for (String alias : action.aliases) {
                if (normalized.equals(alias)
                        || normalized.contains(alias)) {
                    return Optional.of(action);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Validates whether the requested action is structurally possible
     * with the current trip state.
     *
     * IMPORTANT:
     *
     * needsFlights(), needsHotels(), etc. describe the CURRENT plan.
     * They must NOT be used to reject a new user request.
     *
     * Example:
     *
     * Existing plan:
     * needsFlights = false
     *
     * New request:
     * user asks for flight details
     *
     * The flight action must still be allowed.
     */
    public boolean isAllowed(TravelState state) {

        if (state == null) {
            return false;
        }

        return switch (this) {
            case GET_FLIGHT_DETAILS, CHEAPER_FLIGHT,
                    REDUCE_HOTEL_BUDGET, HOTEL_UPGRADE,
                    REMOVE_EXPENSIVE_ATTRACTIONS, GET_HOTEL_DETAILS,
                    GET_WEATHER_DETAILS, GET_BUDGET_BREAKDOWN,
                    ADJUST_ITINERARY -> true;

            case ADD_DESTINATION ->
                state.modification() != null
                        && !TravelState.isBlank(
                                state.modification().getDestination());

        };
    }

    public String wireName() {
        return aliases.get(0);
    }
}