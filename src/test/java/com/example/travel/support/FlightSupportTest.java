package com.example.travel.support;

import com.example.travel.model.FlightOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlightSupportTest {

    @Test
    void dedupesSameFlightNumberAndPrefersTripDate() {
        List<FlightOption> input = List.of(
                flight("JL754", "Japan Airlines", "2026-08-29T02:20:00+00:00", "date=2026-08-29"),
                flight("JL754", "Japan Airlines", "2026-08-30T02:20:00+00:00", "date=2026-08-30"),
                flight("JL754", "Japan Airlines", "2026-08-30T02:20:00+00:00", "date=2026-08-30"),
                flight("6E3821", "IndiGo", "2026-08-30T02:20:00+00:00", "date=2026-08-30"),
                flight("UL3347", "SriLankan", "2026-08-29T02:20:00+00:00", "date=2026-08-29")
        );

        List<FlightOption> result = FlightSupport.dedupePreferDate(input, "2026-08-30", 5);

        assertEquals(3, result.size());
        assertEquals("JL754", result.get(0).getFlightNumber());
        assertEquals("date=2026-08-30", result.get(0).getNotes());
        assertEquals("6E3821", result.get(1).getFlightNumber());
        assertEquals("UL3347", result.get(2).getFlightNumber());
    }

    private static FlightOption flight(String number, String airline, String dep, String notes) {
        return new FlightOption(number, airline, "BLR", "NRT", dep, "", "scheduled", notes);
    }
}
