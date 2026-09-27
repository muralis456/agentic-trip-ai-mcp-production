package com.example.travel.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TripSlotHeuristicsTest {

    @Test
    void japanPromptMapsToTokyoAndSevenDays() {
        String prompt = "Japan 7 days under ₹2 lakh";
        assertEquals("Tokyo", TripSlotHeuristics.extractDestinationHint(prompt));
        assertEquals("Tokyo", TripSlotHeuristics.normalizePlace("Japan"));
        assertEquals(new BigDecimal("200000"),
                com.example.travel.graph.TravelState.parseBudget(TripSlotHeuristics.extractBudgetLabel(prompt)));
        LocalDate start = LocalDate.of(2026, 9, 1);
        assertEquals(LocalDate.of(2026, 9, 7), TripSlotHeuristics.inferReturnDate(prompt, start, start.plusDays(5)));
    }
}
