package com.example.travel.dto;

import java.time.Instant;

public record TripHistoryItem(
        Long id,
        String threadId,
        String title,
        String origin,
        String destination,
        String departureDate,
        String returnDate,
        int travelers,
        String budgetLabel,
        String status,
        boolean awaitingApproval,
        int qualityScore,
        Instant createdAt,
        Instant updatedAt,
        boolean legacy) {
}
