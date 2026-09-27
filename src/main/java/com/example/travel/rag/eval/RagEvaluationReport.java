package com.example.travel.rag.eval;

import java.util.List;

public record RagEvaluationReport(int total, int passed, double passRate, double averageScore,
                                  double averageGroundedness, List<CaseResult> cases) {
    public record CaseResult(String name, double score, boolean passed, String reason) {}
}
