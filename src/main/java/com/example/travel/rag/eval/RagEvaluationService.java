package com.example.travel.rag.eval;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, model-independent RAG evaluation utilities. These metrics are
 * intentionally simple and explainable so they can run in CI without Ollama.
 */
@Service
public class RagEvaluationService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]{3,}");
    private static final Set<String> STOP = Set.of("the","and","for","with","from","that","this","are","was","were","what","how","can","you","your","trip","travel","need","give","please");

    public Evaluation evaluateRetrieval(String query, String context, List<String> sources,
                                        List<String> expectedKeywords, List<String> expectedSources) {
        Set<String> queryTerms = terms(query);
        Set<String> contextTerms = terms(context);
        Set<String> expected = terms(String.join(" ", expectedKeywords == null ? List.of() : expectedKeywords));

        double contextRelevance = overlap(queryTerms, contextTerms);
        double expectedCoverage = expected.isEmpty() ? contextRelevance : overlap(expected, contextTerms);
        double sourceRecall = expectedSources == null || expectedSources.isEmpty()
                ? (sources == null || sources.isEmpty() ? 0d : 1d)
                : overlap(new HashSet<>(expectedSources), new HashSet<>(sources));
        double evidenceAvailability = context == null || context.isBlank() ? 0d : 1d;
        double overall = clamp(0.35 * contextRelevance + 0.35 * expectedCoverage
                + 0.20 * sourceRecall + 0.10 * evidenceAvailability);

        return new Evaluation(round(contextRelevance), round(expectedCoverage), round(sourceRecall),
                round(overall), context != null && !context.isBlank(),
                sources == null ? List.of() : List.copyOf(sources));
    }

    /**
     * Estimates groundedness by measuring how much of an answer's meaningful
     * vocabulary is supported by the supplied retrieved context. This is a
     * screening metric, not a replacement for an LLM judge.
     */
    public double groundedness(String answer, String context) {
        Set<String> answerTerms = terms(answer);
        if (answerTerms.isEmpty()) return 0d;
        return round(overlap(answerTerms, terms(context)));
    }

    private Set<String> terms(String text) {
        if (text == null) return Set.of();
        return TOKEN.matcher(text.toLowerCase(Locale.ROOT)).results()
                .map(m -> m.group())
                .filter(t -> !STOP.contains(t))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private double overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty()) return 0d;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return (double) intersection.size() / left.size();
    }

    private double clamp(double value) { return Math.max(0d, Math.min(1d, value)); }
    private double round(double value) { return Math.round(value * 1000d) / 1000d; }

    public record Evaluation(double contextRelevance, double expectedCoverage, double sourceRecall,
                             double overallScore, boolean evidenceAvailable, List<String> sources) {}
}
