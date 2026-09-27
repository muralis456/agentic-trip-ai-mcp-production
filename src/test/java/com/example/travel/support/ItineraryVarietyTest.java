package com.example.travel.support;

import com.example.travel.model.Itinerary;
import com.example.travel.model.ItineraryActivity;
import com.example.travel.model.ItineraryDay;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItineraryVarietyTest {
    @Test
    void removesRepeatedActivityNamesAcrossDaysWithoutChangingTripLength() {
        Itinerary input = new Itinerary("", List.of(
                new ItineraryDay(1, "Arrival", List.of(new ItineraryActivity("Arrive", "transport", "mixed", true, false, false))),
                new ItineraryDay(2, "Explore Tokyo", List.of(new ItineraryActivity("Visit Shibuya", "sightseeing", "outdoor", true, false, true))),
                new ItineraryDay(3, "Explore Tokyo", List.of(new ItineraryActivity("Visit Shibuya", "sightseeing", "outdoor", true, false, true))),
                new ItineraryDay(4, "Departure", List.of(new ItineraryActivity("Depart", "transport", "mixed", true, false, false)))
        ));
        Itinerary result = ItinerarySupport.normalize(input, 3, "Tokyo", List.of());
        assertEquals(4, result.getDays().size());
        assertEquals(1, result.getDays().get(2).getActivities().size());
        assertEquals("Explore Tokyo — Day 3", result.getDays().get(2).getTitle());
    }
}
