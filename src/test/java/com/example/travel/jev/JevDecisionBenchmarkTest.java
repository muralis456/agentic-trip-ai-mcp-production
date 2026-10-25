package com.example.travel.jev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in local-model benchmark. Run with -Djev.eval=true to call Ollama.
 */
class JevDecisionBenchmarkTest {

    private static final Map<String, String> MODEL_CRITERIA = Map.of(
            "FAST", "Simple, low-risk structured work.",
            "BALANCED", "Normal travel planning and synthesis.",
            "REASONING", "Complex constraints, recovery, or ambiguous multi-step decisions.");
    private static final Map<String, String> RAG_CRITERIA = Map.of(
            "RAG", "Durable knowledge in the internal knowledge base is relevant.",
            "WEB", "Fresh/current web information is required.",
            "BOTH", "Durable internal knowledge and fresh web information are both required.",
            "NONE", "No external knowledge retrieval is needed.");
    private static final Map<String, String> GOAL_CRITERIA = Map.of(
            "REPLAN", "Missing outcomes are recoverable through a changed execution strategy.",
            "HITL", "Automation cannot safely recover; ask the user for a decision or clarification.",
            "ACHIEVED", "All required outcomes are already satisfied.");
    private static final Map<String, String> REPLAN_CRITERIA = Map.of(
            "FLIGHT", "Change flight search/provider/date/price strategy.",
            "HOTEL", "Change hotel search, budget, or accommodation strategy.",
            "BUDGET", "Reduce total cost using configurable travel levers.",
            "ITINERARY", "Regenerate itinerary using the new evidence.",
            "RESEARCH", "Acquire missing destination/research evidence.",
            "WEATHER", "Acquire or refine weather evidence.",
            "ASK_USER", "Ask the user because automation cannot safely resolve the blocker.",
            "NONE", "No replan is required.");
    private static final Map<String, String> HITL_CRITERIA = Map.of(
            "AUTO_COMPLETE", "All deterministic checks pass and the result can be returned without user confirmation.",
            "ASK_USER", "User review is required or material ambiguity remains.");

    @Test
    @EnabledIfSystemProperty(named = "jev.eval", matches = "true")
    void benchmarkConfiguredLocalModelAgainstLabeledDecisions() {
        String baseUrl = System.getProperty("jev.base-url", "http://localhost:11434");
        String model = System.getProperty("jev.model", "nimble:latest");
        String apiKey = System.getProperty("jev.api-key", "ollama");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofMinutes(2));
        JevDecisionClient client = new JevDecisionClient(
                RestClient.builder().requestFactory(requestFactory),
                new ObjectMapper(),
                "ollama",
                baseUrl,
                apiKey,
                model,
                "none",
                baseUrl,
                apiKey,
                model,
                "2m",
                false);
        JevDecisionPolicy policy = benchmarkPolicy();
        List<BenchmarkCase> cases = cases();
        Map<String, KindResults> byKind = new LinkedHashMap<>();
        int correct = 0;
        int accepted = 0;
        long totalLatencyMs = 0;

        System.out.printf(
                "%nJEV benchmark model=%s cases=%d endpoint=%s%n",
                model, cases.size(), baseUrl);
        System.out.println(
                "case | kind | expected | actual | confidence | selectedProbability | margin | exact | policyAccepted | policyReason | latencyMs");

        for (BenchmarkCase benchmarkCase : cases) {
            long started = System.nanoTime();
            JevDecisionClient.JevChoiceDecision decision = client.choose(
                    benchmarkCase.state(),
                    benchmarkCase.instructions(),
                    benchmarkCase.criteria());
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            JevDecisionPolicy.Acceptance result =
                    policy.acceptanceReason(benchmarkCase.kind(), decision);
            boolean exact = benchmarkCase.expected().equalsIgnoreCase(decision.choice());
            if (exact) correct++;
            if (result.accepted()) accepted++;
            totalLatencyMs += latencyMs;

            KindResults kindResults = byKind.computeIfAbsent(
                    benchmarkCase.kind().name(), ignored -> new KindResults());
            kindResults.total++;
            if (exact) kindResults.correct++;
            if (result.accepted()) kindResults.accepted++;

            System.out.printf(
                    Locale.ROOT,
                    "%s | %s | %s | %s | %.3f | %.3f | %.3f | %s | %s | %s | %d%n",
                    benchmarkCase.id(),
                    benchmarkCase.kind(),
                    benchmarkCase.expected(),
                    decision.choice(),
                    decision.confidence(),
                    result.selectedProbability(),
                    result.margin(),
                    exact,
                    result.accepted(),
                    result.reason(),
                    latencyMs);
        }

        System.out.printf(
                Locale.ROOT,
                "SUMMARY exactAccuracy=%d/%d (%.1f%%) policyAccepted=%d/%d (%.1f%%) averageLatencyMs=%.0f%n",
                correct,
                cases.size(),
                percentage(correct, cases.size()),
                accepted,
                cases.size(),
                percentage(accepted, cases.size()),
                (double) totalLatencyMs / cases.size());
        byKind.forEach((kind, results) -> System.out.printf(
                Locale.ROOT,
                "BY_KIND kind=%s exactAccuracy=%d/%d (%.1f%%) policyAccepted=%d/%d (%.1f%%)%n",
                kind,
                results.correct,
                results.total,
                percentage(results.correct, results.total),
                results.accepted,
                results.total,
                percentage(results.accepted, results.total)));
    }

    private static List<BenchmarkCase> cases() {
        List<BenchmarkCase> cases = new ArrayList<>();
        cases.add(choice(
                "model-simple-weather",
                JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                Map.of("request", "What should I pack for a Tokyo trip?", "currentPolicy", "BALANCED",
                        "complexity", "SIMPLE", "tripPlanning", false, "requestType", "GENERAL",
                        "needsFlights", false, "needsHotels", false, "needsResearch", false,
                        "needsItinerary", false, "needsKnowledge", true),
                "Choose the model policy for this graph turn. Prefer FAST for simple extraction, BALANCED for normal planning, and REASONING for complex multi-constraint recovery.",
                MODEL_CRITERIA,
                "FAST"));
        cases.add(choice(
                "model-standard-trip",
                JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                Map.of("request", "Plan a one-week Tokyo trip with flights, hotels and an itinerary.",
                        "currentPolicy", "BALANCED", "complexity", "SIMPLE", "tripPlanning", true,
                        "requestType", "TRIP_PLANNING", "needsFlights", true, "needsHotels", true,
                        "needsResearch", true, "needsItinerary", true, "needsKnowledge", true),
                "Choose the model policy for this graph turn. Prefer FAST for simple extraction, BALANCED for normal planning, and REASONING for complex multi-constraint recovery.",
                MODEL_CRITERIA,
                "BALANCED"));
        cases.add(choice(
                "model-complex-recovery",
                JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                Map.of("request", "Recover a multi-city trip with a strict budget, no direct flights, conflicting dates and a hotel over budget.",
                        "currentPolicy", "BALANCED", "complexity", "COMPLEX", "tripPlanning", true,
                        "requestType", "TRIP_PLANNING", "needsFlights", true, "needsHotels", true,
                        "needsResearch", true, "needsItinerary", true, "needsKnowledge", false),
                "Choose the model policy for this graph turn. Prefer FAST for simple extraction, BALANCED for normal planning, and REASONING for complex multi-constraint recovery.",
                MODEL_CRITERIA,
                "REASONING"));

        cases.add(choice(
                "rag-durable-etiquette",
                JevDecisionPolicy.DecisionKind.RAG,
                Map.of("request", "What etiquette should I know when using public transport in Tokyo?",
                        "destination", "Tokyo", "needsKnowledge", true, "existingRagContext", false),
                "Choose the evidence route for this request. Use RAG for durable knowledge, WEB for current/live information, BOTH when both are needed, and NONE when no external knowledge retrieval is needed.",
                RAG_CRITERIA,
                "RAG"));
        cases.add(choice(
                "rag-live-availability",
                JevDecisionPolicy.DecisionKind.RAG,
                Map.of("request", "Find current flight fares and hotel availability for Tokyo next month.",
                        "destination", "Tokyo", "needsKnowledge", true, "existingRagContext", false),
                "Choose the evidence route for this request. Use RAG for durable knowledge, WEB for current/live information, BOTH when both are needed, and NONE when no external knowledge retrieval is needed.",
                RAG_CRITERIA,
                "WEB"));
        cases.add(choice(
                "rag-durable-and-live",
                JevDecisionPolicy.DecisionKind.RAG,
                Map.of("request", "Explain Tokyo transit etiquette and check current service closures for my travel dates.",
                        "destination", "Tokyo", "needsKnowledge", true, "existingRagContext", false),
                "Choose the evidence route for this request. Use RAG for durable knowledge, WEB for current/live information, BOTH when both are needed, and NONE when no external knowledge retrieval is needed.",
                RAG_CRITERIA,
                "BOTH"));
        cases.add(choice(
                "rag-no-external-knowledge",
                JevDecisionPolicy.DecisionKind.RAG,
                Map.of("request", "Rewrite this sentence in a polite tone: I will arrive late.",
                        "destination", "", "needsKnowledge", false, "existingRagContext", false),
                "Choose the evidence route for this request. Use RAG for durable knowledge, WEB for current/live information, BOTH when both are needed, and NONE when no external knowledge retrieval is needed.",
                RAG_CRITERIA,
                "NONE"));

        cases.add(choice(
                "goal-recoverable-flight",
                JevDecisionPolicy.DecisionKind.GOAL,
                Map.of("goal", "Plan a Tokyo trip with flights and hotels.",
                        "status", "PARTIAL", "reason", "Flight provider returned no usable offers.",
                        "unmet", List.of("flight results"), "blocking", List.of()),
                "Choose the next lifecycle route. Do not claim success when required outcomes are missing.",
                GOAL_CRITERIA,
                "REPLAN"));
        cases.add(choice(
                "goal-unrecoverable-input",
                JevDecisionPolicy.DecisionKind.GOAL,
                Map.of("goal", "Plan a trip using the user's requested departure city.",
                        "status", "FAILED", "reason", "The origin airport cannot be resolved.",
                        "unmet", List.of("origin airport"), "blocking", List.of("user must provide a departure city")),
                "Choose the next lifecycle route. Do not claim success when required outcomes are missing.",
                GOAL_CRITERIA,
                "HITL"));

        cases.add(choice(
                "replan-flight-failure",
                JevDecisionPolicy.DecisionKind.REPLAN,
                Map.of("request", "Find a flight from Bengaluru to Tokyo.",
                        "unmet", List.of("flight results unavailable"),
                        "blocking", List.of("flight provider returned no offers"),
                        "proposedActions", List.of("retry with flexible dates", "change flight provider")),
                "Choose the single highest-value replan lever. Select only a capability that can address the unmet outcome. Do not choose ASK_USER unless automation cannot safely recover; choose NONE only when no replan is needed.",
                REPLAN_CRITERIA,
                "FLIGHT"));
        cases.add(choice(
                "replan-budget-overrun",
                JevDecisionPolicy.DecisionKind.REPLAN,
                Map.of("request", "Plan a Tokyo trip under 200000 INR.",
                        "unmet", List.of("total budget exceeded"),
                        "blocking", List.of("hotel costs exceed the remaining budget"),
                        "proposedActions", List.of("search lower-cost hotels", "reduce trip costs")),
                "Choose the single highest-value replan lever. Select only a capability that can address the unmet outcome. Do not choose ASK_USER unless automation cannot safely recover; choose NONE only when no replan is needed.",
                REPLAN_CRITERIA,
                "BUDGET"));

        cases.add(choice(
                "hitl-completed-valid-plan",
                JevDecisionPolicy.DecisionKind.HITL,
                Map.of("request", "Return the completed Tokyo travel plan.",
                        "tripPlanning", true, "goalStatus", "ACHIEVED", "validationErrors", List.of()),
                "Decide whether this completed result can be returned automatically or should pause for user review. Never auto-complete when validation is unresolved or the goal is not achieved.",
                HITL_CRITERIA,
                "AUTO_COMPLETE"));
        return List.copyOf(cases);
    }

    private static BenchmarkCase choice(
            String id,
            JevDecisionPolicy.DecisionKind kind,
            Map<String, Object> state,
            String instructions,
            Map<String, String> criteria,
            String expected) {
        return new BenchmarkCase(id, kind, state, instructions, criteria, expected);
    }

    private static JevDecisionPolicy benchmarkPolicy() {
        return new JevDecisionPolicy(
                0.50, 0.75, 0.15,
                0.0, 0.60, 0.15,
                0.0, 0.70, 0.15,
                0.50, 0.75, 0.10,
                0.80, 0.80, 0.20,
                0.50, 0.75, 0.15,
                0.75, 0.90, 0.75,
                0.75, 0.75);
    }

    private static double percentage(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private record BenchmarkCase(
            String id,
            JevDecisionPolicy.DecisionKind kind,
            Map<String, Object> state,
            String instructions,
            Map<String, String> criteria,
            String expected) {
    }

    private static final class KindResults {
        private int total;
        private int correct;
        private int accepted;
    }
}
