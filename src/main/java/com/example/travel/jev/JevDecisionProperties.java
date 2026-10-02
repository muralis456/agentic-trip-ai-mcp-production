package com.example.travel.jev;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "travel.jev")
public record JevDecisionProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        double minimumConfidence) {
}
