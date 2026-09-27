package com.example.travel.eval;

import com.example.travel.rag.eval.RagEvaluationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagEvaluationServiceTest {
    private final RagEvaluationService evaluator = new RagEvaluationService();

    @Test
    void relevantContextGetsHighScore() {
        var result = evaluator.evaluateRetrieval(
                "rainy trip packing umbrella jacket",
                "Pack a rain jacket and umbrella for wet weather.",
                List.of("travel-packing-and-culture.md"),
                List.of("rain", "umbrella", "jacket"),
                List.of("travel-packing-and-culture.md"));
        assertTrue(result.overallScore() >= 0.70, result.toString());
        assertEquals(1.0, result.sourceRecall());
    }

    @Test
    void unrelatedContextGetsLowScore() {
        var result = evaluator.evaluateRetrieval(
                "visa requirements for Japan",
                "Pack comfortable shoes and carry a reusable water bottle.",
                List.of("travel-packing-and-culture.md"),
                List.of("visa", "passport", "Japan"),
                List.of("visa-guide.md"));
        assertTrue(result.overallScore() < 0.50, result.toString());
    }

    @Test
    void groundednessMeasuresAnswerSupport() {
        double score = evaluator.groundedness(
                "Pack a rain jacket and umbrella.",
                "For rainy weather, pack a rain jacket and umbrella.");
        assertTrue(score >= 0.80, "score=" + score);
    }
}
