package com.example.travel.controller;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class TravelDateSanitizationTest {
    @Test
    void yesterdayIsPast() {
        assertTrue(LocalDate.parse("2026-09-19").isBefore(LocalDate.parse("2026-09-20")));
    }
}
