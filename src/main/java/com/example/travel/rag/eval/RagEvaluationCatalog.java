package com.example.travel.rag.eval;

import java.util.List;

public final class RagEvaluationCatalog {
    private RagEvaluationCatalog() {}

    public static List<RagEvaluationCase> defaultCases() {
        return List.of(
                new RagEvaluationCase("packing", "What should I pack for a rainy city trip?",
                        List.of("rain", "jacket", "umbrella", "pack"), List.of("travel-packing-and-culture.md"), 0.45),
                new RagEvaluationCase("culture", "What local culture and etiquette should I consider?",
                        List.of("culture", "etiquette", "local", "respect"), List.of("travel-packing-and-culture.md"), 0.40),
                new RagEvaluationCase("safety", "What basic travel safety practices should I follow?",
                        List.of("safety", "emergency", "documents", "insurance"), List.of("trip-safety-basics.md"), 0.40),
                new RagEvaluationCase("planning", "Give me practical travel planning guidance for a family trip.",
                        List.of("planning", "family", "budget", "itinerary"), List.of("travel-planning-guide.md"), 0.40),
                new RagEvaluationCase("live-data-boundary", "Can you tell me today's flight availability?",
                        List.of("flight", "availability"), List.of(), 0.10)
        );
    }
}
