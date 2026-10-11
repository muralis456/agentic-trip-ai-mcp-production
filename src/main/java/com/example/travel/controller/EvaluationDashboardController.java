package com.example.travel.controller;

import com.example.travel.observability.AgentEvaluationLedger;
import com.example.travel.observability.AgentModelUsageLedger;
import com.example.travel.observability.AgentRuntimeEventLedger;
import com.example.travel.observability.ModelPricingProperties;
import com.example.travel.repository.AgentModelUsageAggregate;
import com.example.travel.repository.AgentModelUsageTripAggregate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
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
    private final AgentRuntimeEventLedger runtimeEventLedger;
    private final MeterRegistry meters;
    private final ModelPricingProperties pricing;

    public EvaluationDashboardController(AgentEvaluationLedger evaluationLedger,
                                         AgentModelUsageLedger modelUsageLedger,
                                         AgentRuntimeEventLedger runtimeEventLedger,
                                         MeterRegistry meters,
                                         ModelPricingProperties pricing) {
        this.evaluationLedger = evaluationLedger;
        this.modelUsageLedger = modelUsageLedger;
        this.runtimeEventLedger = runtimeEventLedger;
        this.meters = meters;
        this.pricing = pricing;
    }

    @GetMapping("/evaluation")
    public String page() { return "evaluation-dashboard"; }

    @ResponseBody
    @GetMapping("/api/observability/dashboard")
    public Map<String, Object> dashboard(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            Authentication authentication) {
        String userId = authentication == null ? "" : authentication.getName();
        int days = Math.max(0, Math.min(3650, windowDays));
        int safePageSize = Math.max(1, Math.min(100, pageSize == 50 ? limit : pageSize));
        int safePage = Math.max(0, page);

        Map<String, Object> summary = new LinkedHashMap<>(evaluationLedger.summary(userId, days));
        List<AgentModelUsageAggregate> usageAggregates = modelUsageLedger.aggregateForUser(userId, days);
        Map<String, ModelUsage> models = new TreeMap<>();
        long inputTokens = 0, outputTokens = 0, llmCalls = 0, llmErrors = 0, durationMs = 0;
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

        // Keep process-wide metrics explicitly separate from account-scoped persisted events.
        Map<String, Object> processRuntime = new LinkedHashMap<>();
        processRuntime.put("nodeExecutions", counterSum("agent.nodes"));
        processRuntime.put("replans", counterSum("agent.replans"));
        processRuntime.put("mcpCalls", counterSum("agent.mcp.calls"));
        processRuntime.put("mcpRetries", counterSum("agent.mcp.retries"));
        processRuntime.put("providerCalls", counterSum("agent.provider.calls"));
        processRuntime.put("agentRetries", counterSum("agent.retries"));
        processRuntime.put("averageNodeDurationMs", timerAverageMs("agent.node.duration"));
        processRuntime.put("averageMcpDurationMs", timerAverageMs("agent.mcp.duration"));
        processRuntime.put("averageProviderDurationMs", timerAverageMs("agent.provider.duration"));

        List<Map<String, Object>> modelUsage = models.values().stream()
                .sorted(Comparator.comparingLong((ModelUsage m) -> m.inputTokens + m.outputTokens).reversed())
                .map(m -> m.asMap(pricing)).toList();

        Map<String, TripCost> trips = new TreeMap<>();
        for (AgentModelUsageTripAggregate usage : modelUsageLedger.aggregateTripsForUser(userId, days)) {
            String threadId = usage.getThreadId();
            if (threadId == null || threadId.isBlank() || "untracked".equals(threadId)) continue;
            TripCost trip = trips.computeIfAbsent(threadId, TripCost::new);
            long calls = usage.getCalls() == null ? 0 : usage.getCalls();
            long input = usage.getInputTokens() == null ? 0 : usage.getInputTokens();
            long output = usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
            long duration = usage.getDurationMs() == null ? 0 : usage.getDurationMs();
            trip.calls += calls;
            trip.inputTokens += input;
            trip.outputTokens += output;
            Double local = pricing.estimateLocalInfrastructureCostUsd(usage.getModel(), duration);
            if (local == null) trip.localCostKnown = false; else trip.localInfrastructureCostUsd += local;
            Double provider = pricing.estimateProviderApiCostUsd(usage.getModel(), input, output);
            if (provider == null) trip.providerCostKnown = false; else trip.providerApiCostUsd += provider;
        }
        Map<String, String> tripLabels = evaluationLedger.labelsForThreads(userId, trips.keySet());
        List<Map<String, Object>> tripCosts = trips.values().stream()
                .sorted(Comparator.comparingLong((TripCost t) -> t.calls).reversed())
                .map(t -> t.asMap(tripLabels.get(t.threadId))).toList();
        boolean costsComplete = trips.values().stream().allMatch(t -> t.localCostKnown && t.providerCostKnown);
        Map<String, Object> costSummary = new LinkedHashMap<>();
        costSummary.put("localInfrastructureCostUsd", trips.values().stream().allMatch(t -> t.localCostKnown)
                ? trips.values().stream().mapToDouble(t -> t.localInfrastructureCostUsd).sum() : null);
        costSummary.put("providerApiCostUsd", trips.values().stream().allMatch(t -> t.providerCostKnown)
                ? trips.values().stream().mapToDouble(t -> t.providerApiCostUsd).sum() : null);
        costSummary.put("totalCostUsd", costsComplete ? trips.values().stream()
                .mapToDouble(t -> t.localInfrastructureCostUsd + t.providerApiCostUsd).sum() : null);
        costSummary.put("costsComplete", costsComplete);
        costSummary.put("trips", tripCosts.size());

        Page<AgentEvaluationLedger.EvaluationRecord> tracePage =
                evaluationLedger.search(userId, days, safePage, safePageSize, status, q);
        List<Map<String, Object>> runtimeAggregate = runtimeEventLedger.aggregateForUser(userId, days);
        List<Map<String, Object>> runtimeEvents = runtimeEventLedger.recentForUser(userId, days, 100);

        Map<String, Object> tracePagination = new LinkedHashMap<>();
        tracePagination.put("page", tracePage.getNumber());
        tracePagination.put("pageSize", tracePage.getSize());
        tracePagination.put("totalElements", tracePage.getTotalElements());
        tracePagination.put("totalPages", tracePage.getTotalPages());
        tracePagination.put("hasNext", tracePage.hasNext());
        tracePagination.put("hasPrevious", tracePage.hasPrevious());

        return Map.of(
                "summary", summary,
                "traces", tracePage.getContent(),
                "tracePagination", tracePagination,
                "modelUsage", modelUsage,
                "tripCosts", tripCosts,
                "costSummary", costSummary,
                "processRuntime", processRuntime,
                "runtimeSummary", runtimeAggregate,
                "runtimeEvents", runtimeEvents,
                "refreshedAt", Instant.now().toString(),
                "modelUsageWindowDays", days,
                "costNotice", "Model API amounts are token-price estimates, not provider invoices. Local compute cost is unavailable unless TRAVEL_LOCAL_COMPUTE_USD_PER_HOUR is explicitly configured. Unknown costs remain unavailable, never zero-filled. Separate non-model API billing is not included.");
    }

    private double counterSum(String name) {
        return meters.getMeters().stream()
                .filter(m -> name.equals(m.getId().getName()))
                .filter(io.micrometer.core.instrument.Counter.class::isInstance)
                .map(io.micrometer.core.instrument.Counter.class::cast)
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private double timerAverageMs(String name) {
        return meters.getMeters().stream()
                .filter(m -> name.equals(m.getId().getName()))
                .filter(io.micrometer.core.instrument.Timer.class::isInstance)
                .map(io.micrometer.core.instrument.Timer.class::cast)
                .mapToDouble(t -> t.count() == 0 ? 0 : t.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) / t.count())
                .average().orElse(0);
    }

    private static final class TripCost {
        private final String threadId;
        private long calls, inputTokens, outputTokens;
        private double localInfrastructureCostUsd, providerApiCostUsd;
        private boolean localCostKnown = true, providerCostKnown = true;
        private TripCost(String threadId) { this.threadId = threadId; }
        private Map<String, Object> asMap(String label) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threadId", threadId);
            row.put("label", label == null || label.isBlank() ? "Trip · " + threadId.substring(Math.max(0, threadId.length() - 8)) : label);
            row.put("calls", calls);
            row.put("inputTokens", inputTokens);
            row.put("outputTokens", outputTokens);
            row.put("localInfrastructureCostUsd", localCostKnown ? localInfrastructureCostUsd : null);
            row.put("providerApiCostUsd", providerCostKnown ? providerApiCostUsd : null);
            row.put("totalCostUsd", localCostKnown && providerCostKnown ? localInfrastructureCostUsd + providerApiCostUsd : null);
            row.put("costKnown", localCostKnown && providerCostKnown);
            return row;
        }
    }

    private static final class ModelUsage {
        private final String model;
        private final long inputTokens, outputTokens, calls, errors, durationMs;
        private ModelUsage(AgentModelUsageAggregate a) {
            model = a.getModel();
            calls = a.getCalls() == null ? 0 : a.getCalls();
            errors = a.getErrors() == null ? 0 : a.getErrors();
            inputTokens = a.getInputTokens() == null ? 0 : a.getInputTokens();
            outputTokens = a.getOutputTokens() == null ? 0 : a.getOutputTokens();
            durationMs = a.getDurationMs() == null ? 0 : a.getDurationMs();
        }
        private Map<String, Object> asMap(ModelPricingProperties pricing) {
            Map<String, Object> row = new LinkedHashMap<>();
            Double api = pricing.estimateProviderApiCostUsd(model, inputTokens, outputTokens);
            Double local = pricing.estimateLocalInfrastructureCostUsd(model, durationMs);
            row.put("model", model);
            row.put("calls", calls);
            row.put("errors", errors);
            row.put("errorRate", calls == 0 ? 0.0 : (double) errors / calls);
            row.put("averageDurationMs", calls == 0 ? 0L : durationMs / calls);
            row.put("inputTokens", inputTokens);
            row.put("outputTokens", outputTokens);
            row.put("totalTokens", inputTokens + outputTokens);
            row.put("providerApiCostUsd", api);
            row.put("localInfrastructureCostUsd", local);
            row.put("totalCostUsd", api != null && local != null ? api + local : null);
            row.put("providerApiCostKind", pricing.isLocalModel(model) ? "LOCAL_NO_PROVIDER_API_CHARGE" : api == null ? "UNPRICED" : "TOKEN_PRICE_ESTIMATE");
            row.put("localCostConfigured", local != null && pricing.isLocalModel(model));
            row.put("estimatedCostUsd", api);
            return row;
        }
    }
}
