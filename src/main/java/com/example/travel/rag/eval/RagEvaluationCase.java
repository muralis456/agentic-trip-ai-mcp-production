package com.example.travel.rag.eval;

import java.util.List;

public record RagEvaluationCase(String name, String query, List<String> expectedKeywords,
                                List<String> expectedSources, double minimumScore) {}
