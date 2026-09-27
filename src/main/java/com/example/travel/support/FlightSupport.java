package com.example.travel.support;

import com.example.travel.model.FlightOption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AviationStack free-plan responses often repeat the same flight number across dates
 * and codeshare partners. Prefer the trip date and keep unique flight numbers only.
 */
public final class FlightSupport {

    private FlightSupport() {
    }

    public static List<FlightOption> dedupePreferDate(List<FlightOption> flights, String tripDate, int limit) {
        if (flights == null || flights.isEmpty()) {
            return List.of();
        }
        List<FlightOption> sorted = new ArrayList<>(flights);
        sorted.sort(Comparator
                .comparing((FlightOption f) -> !matchesTripDate(f, tripDate))
                .thenComparing(f -> nullToEmpty(f.getDepartureTime())));

        Map<String, FlightOption> unique = new LinkedHashMap<>();
        for (FlightOption flight : sorted) {
            if ("unavailable".equalsIgnoreCase(nullToEmpty(flight.getStatus()))
                    && TravelStateSafe.isBlank(flight.getFlightNumber())) {
                unique.putIfAbsent("unavailable", flight);
                continue;
            }
            String key = dedupeKey(flight);
            unique.putIfAbsent(key, flight);
            if (unique.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String dedupeKey(FlightOption flight) {
        String number = nullToEmpty(flight.getFlightNumber()).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (!number.isBlank() && !number.equalsIgnoreCase("Unavailable")) {
            return number;
        }
        return nullToEmpty(flight.getAirline()).toLowerCase(Locale.ROOT)
                + "|" + nullToEmpty(flight.getDepartureTime());
    }

    private static boolean matchesTripDate(FlightOption flight, String tripDate) {
        if (tripDate == null || tripDate.isBlank()) {
            return false;
        }
        String notes = nullToEmpty(flight.getNotes());
        String dep = nullToEmpty(flight.getDepartureTime());
        return notes.contains(tripDate) || dep.startsWith(tripDate);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Tiny blank helper to avoid coupling FlightSupport to TravelState. */
    private static final class TravelStateSafe {
        static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
