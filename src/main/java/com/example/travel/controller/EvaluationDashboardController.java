package com.example.travel.controller;

import com.example.travel.repository.AgentModelUsageAggregate;
import com.example.travel.observability.AgentEvaluationLedger;
import com.example.travel.observability.AgentModelUsageLedger;
import com.example.travel.observability.ModelPricingProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Controller
public class EvaluationDashboardController {
    private final AgentEvaluationLedger evaluationLedger;
    private final AgentModelUsageLedger modelUsageLedger;
    private final MeterRegistry meters;
    private final ModelPricingProperties pricing;

    public EvaluationDashboardController(AgentEvaluationLedger evaluationLedger,
                                         AgentModelUsageLedger modelUsageLedger,
                                         MeterRegistry meters,
                                         ModelPricingProperties pricing) {
        this.evaluationLedger = evaluationLedger;
        this.modelUsageLedger = modelUsageLedger;
        this.meters = meters;
        this.pricing = pricing;
    }

    @GetMapping("/evaluation")
    public String page() {
        return "evaluation-dashboard";
    }

    @ResponseBody
    @GetMapping("/api/observability/dashboard")
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "30") int windowDays,
                                         Authentication authentication) {
        String userId = authentication == null ? "" : authentication.getName();
        int days = Math.max(1, Math.min(365, windowDays));
        List<AgentEvaluationLedger.EvaluationRecord> recent = evaluationLedger.recent(userId, limit);
        Map<String, Object> summary = new LinkedHashMap<>(evaluationLedger.summary(userId));

        // Durable model usage is scoped to the authenticated account and selected time window.
        List<AgentModelUsageAggregate> usageAggregates = modelUsageLedger.aggregateForUser(userId, days);
        Map<String, ModelUsage> models = new TreeMap<>();
        long inputTokens = 0;
        long outputTokens = 0;
        long llmCalls = 0;
        long llmErrors = 0;
        long durationMs = 0;
        for (AgentModelUsageAggregate aggregate : usageAggregates) {
            ModelUsage usage = new ModelUsage(aggregate);
            models.put(aggregate.getModel(), usage);
            inputTokens += usage.inputTokens;
            outputTokens += usage.outputTokens;
            llmCalls += usage.calls;
            llmErrors += usage.errors;
            durationMs += usage.durationMs;
        }
        summary.put("llmCalls", llmCalls);
        summary.put("inputTokens", inputTokens);
        summary.put("outputTokens", outputTokens);
        summary.put("llmErrors", llmErrors);
        summary.put("llmErrorRate", llmCalls == 0 ? 0.0 : (double) llmErrors / llmCalls);
        summary.put("averageLlmDurationMs", llmCalls == 0 ? 0L : durationMs / llmCalls);
        summary.put("modelUsageWindowDays", days);
        summary.put("modelUsageRetention", "durable PostgreSQL events scoped to your account");

        // MCP/replan/node counters currently have no user identity and remain explicitly process-wide.
        summary.put("mcpCalls", counterSum("agent.mcp.calls"));
        summary.put("replans", counterSum("agent.replans"));
        summary.put("nodeExecutions", counterSum("agent.nodes"));

        List<Map<String, Object>> modelUsage = models.values().stream()
                .sorted(Comparator.comparingLong((ModelUsage m) -> m.inputTokens + m.outputTokens).reversed())
                .map(m -> m.asMap(pricing)).toList();

        return Map.of(
                "summary", summary,
                "traces", recent,
                "modelUsage", modelUsage,
                "refreshedAt", Instant.now().toString(),
                "modelUsageWindowDays", days,
                "costNotice", "Model usage is durably aggregated in PostgreSQL and account-scoped for the selected window. Cost is estimated only when provider/model pricing is explicitly configured.");
    }

    private double counterSum(String name) {
        return meters.getMeters().stream()
                .filter(m -> name.equals(m.getId().getName()))
                .filter(io.micrometer.core.instrument.Counter.class::isInstance)
                .map(io.micrometer.core.instrument.Counter.class::cast)
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private static final class ModelUsage {
        private final String model;
        private long inputTokens;
        private long outputTokens;
        private long calls;
        private long errors;
        private long durationMs;

        private ModelUsage(AgentModelUsageAggregate aggregate) {
            this.model = aggregate.getModel();
            this.calls = aggregate.getCalls() == null ? 0 : aggregate.getCalls();
            this.errors = aggregate.getErrors() == null ? 0 : aggregate.getErrors();
            this.inputTokens = aggregate.getInputTokens() == null ? 0 : aggregate.getInputTokens();
            this.outputTokens = aggregate.getOutputTokens() == null ? 0 : aggregate.getOutputTokens();
            this.durationMs = aggregate.getDurationMs() == null ? 0 : aggregate.getDurationMs();
        }

        private Map<String, Object> asMap(ModelPricingProperties pricing) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("model", model);
            result.put("calls", calls);
            result.put("errors", errors);
            result.put("errorRate", calls == 0 ? 0.0 : (double) errors / calls);
            result.put("averageDurationMs", calls == 0 ? 0L : durationMs / calls);
            result.put("inputTokens", inputTokens);
            result.put("outputTokens", outputTokens);
            result.put("totalTokens", inputTokens + outputTokens);
            var rate = pricing.getModelPricing().get(model);
            if (rate == null) {
                String normalizedModel = normalizeModel(model);
                rate = pricing.getModelPricing().entrySet().stream()
                        .filter(e -> normalizeModel(e.getKey()).equals(normalizedModel))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            Double cost = rate == null ? null :
                    (inputTokens * rate.getInputUsdPerMillion() + outputTokens * rate.getOutputUsdPerMillion()) / 1_000_000.0;
            result.put("estimatedCostUsd", cost);
            return result;
        }

        private static String normalizeModel(String value) {
            return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        }
    }
}
