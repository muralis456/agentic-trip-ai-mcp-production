package com.example.travel.controller;

import com.example.travel.observability.AgentEvaluationLedger;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Controller
public class EvaluationDashboardController {
    private final AgentEvaluationLedger ledger;
    private final MeterRegistry meters;
    private final com.example.travel.observability.ModelPricingProperties pricing;

    public EvaluationDashboardController(AgentEvaluationLedger ledger, MeterRegistry meters,
                                         com.example.travel.observability.ModelPricingProperties pricing) {
        this.ledger = ledger;
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
                                          Authentication authentication) {
        String userId = authentication == null ? "" : authentication.getName();
        List<AgentEvaluationLedger.EvaluationRecord> recent = ledger.recent(userId, limit);
        Map<String, Object> summary = new LinkedHashMap<>(ledger.summary(userId));
        summary.put("llmCalls", counterSum("agent.llm.calls"));
        summary.put("inputTokens", counterSum("agent.llm.tokens.input"));
        summary.put("outputTokens", counterSum("agent.llm.tokens.output"));
        summary.put("mcpCalls", counterSum("agent.mcp.calls"));
        summary.put("replans", counterSum("agent.replans"));
        summary.put("nodeExecutions", counterSum("agent.nodes"));

        Map<String, ModelUsage> models = new TreeMap<>();
        for (Meter meter : meters.getMeters()) {
            String name = meter.getId().getName();
            String model = meter.getId().getTag("model");
            if (model == null) continue;
            ModelUsage usage = models.computeIfAbsent(model, ModelUsage::new);
            if ("agent.llm.tokens.input".equals(name) && meter instanceof io.micrometer.core.instrument.Counter c) {
                usage.inputTokens += (long) c.count();
            } else if ("agent.llm.tokens.output".equals(name) && meter instanceof io.micrometer.core.instrument.Counter c) {
                usage.outputTokens += (long) c.count();
            } else if ("agent.llm.calls".equals(name) && meter instanceof io.micrometer.core.instrument.Counter c) {
                usage.calls += (long) c.count();
            }
        }
        List<Map<String, Object>> modelUsage = models.values().stream()
                .sorted(Comparator.comparingLong((ModelUsage m) -> m.inputTokens + m.outputTokens).reversed())
                .map(m -> m.asMap(pricing)).toList();

        return Map.of(
                "summary", summary,
                "traces", recent,
                "modelUsage", modelUsage,
                "refreshedAt", java.time.Instant.now().toString(),
                "costNotice", "Token usage is measured. Monetary cost is not estimated until provider/model-specific pricing is configured.");
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

        private ModelUsage(String model) { this.model = model; }

        private Map<String, Object> asMap(com.example.travel.observability.ModelPricingProperties pricing) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("model", model);
            result.put("calls", calls);
            result.put("inputTokens", inputTokens);
            result.put("outputTokens", outputTokens);
            result.put("totalTokens", inputTokens + outputTokens);
            var rate = pricing.getModelPricing().get(model);
            if (rate == null) {
                rate = pricing.getModelPricing().entrySet().stream()
                        .filter(e -> normalizeModel(e.getKey()).equals(model))
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
