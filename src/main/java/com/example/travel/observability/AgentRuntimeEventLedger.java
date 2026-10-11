package com.example.travel.observability;

import com.example.travel.entity.AgentRuntimeEvent;
import com.example.travel.repository.AgentRuntimeEventAggregate;
import com.example.travel.repository.AgentRuntimeEventRepository;
import com.example.travel.service.ModelRoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable, user/thread-scoped operational events; never stores prompts or tool arguments. */
@Service
public class AgentRuntimeEventLedger {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeEventLedger.class);
    private final AgentRuntimeEventRepository repository;
    private final ModelPricingProperties pricing;

    public AgentRuntimeEventLedger(AgentRuntimeEventRepository repository, ModelPricingProperties pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recordCurrentRun(String type, String component, String status, long durationMs, int attempt) {
        String userId = ModelRoutingContext.userId();
        String threadId = ModelRoutingContext.threadId();
        if (userId == null || userId.isBlank() || threadId == null || threadId.isBlank()) return;
        try {
            Double cost = "PROVIDER".equalsIgnoreCase(type) ? pricing.estimateProviderCallCostUsd(component) : null;
            repository.save(new AgentRuntimeEvent(userId, threadId, safe(type, 40), safe(component, 120),
                    safe(status, 16), durationMs, attempt, cost, Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("Could not persist runtime telemetry; continuing without durable event ({})",
                    ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> aggregateForUser(String userId, int days) {
        if (userId == null || userId.isBlank()) return List.of();
        int safeDays = Math.max(0, Math.min(3650, days));
        Instant since = safeDays == 0 ? null : Instant.now().minusSeconds(safeDays * 86400L);
        return (since == null ? repository.aggregateForUser(userId) : repository.aggregateForUserSince(userId, since))
                .stream().map(this::asMap).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> providerCostsByThread(String userId, int days) {
        if (userId == null || userId.isBlank()) return List.of();
        int safeDays = Math.max(0, Math.min(3650, days));
        Instant since = safeDays == 0 ? null : Instant.now().minusSeconds(safeDays * 86400L);
        return (since == null ? repository.providerCostsByThread(userId) : repository.providerCostsByThreadSince(userId, since))
                .stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threadId", a.getThreadId());
            row.put("calls", a.getCalls() == null ? 0L : a.getCalls());
            row.put("pricedCalls", a.getPricedCalls() == null ? 0L : a.getPricedCalls());
            row.put("unpricedCalls", a.getUnpricedCalls() == null ? 0L : a.getUnpricedCalls());
            row.put("costUsd", a.getCostUsd());
            row.put("costKnown", a.getUnpricedCalls() != null && a.getUnpricedCalls() == 0L);
            return row;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentForUser(String userId, int days, int limit) {
        if (userId == null || userId.isBlank()) return List.of();
        int safeDays = Math.max(0, Math.min(3650, days));
        Instant since = safeDays == 0 ? null : Instant.now().minusSeconds(safeDays * 86400L);
        var pageable = PageRequest.of(0, Math.max(1, Math.min(200, limit)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return (since == null ? repository.recentForUser(userId, pageable)
                : repository.recentForUserSince(userId, since, pageable))
                .getContent().stream().map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("timestamp", e.getCreatedAt().toString());
                    row.put("threadId", e.getThreadId());
                    row.put("eventType", e.getEventType());
                    row.put("component", e.getComponent());
                    row.put("status", e.getStatus());
                    row.put("durationMs", e.getDurationMs());
                    row.put("attempt", e.getAttempt());
                    row.put("costUsd", e.getCostUsd());
                    return row;
                }).toList();
    }

    private Map<String, Object> asMap(AgentRuntimeEventAggregate a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventType", a.getEventType());
        row.put("component", a.getComponent());
        row.put("status", a.getStatus());
        row.put("count", a.getCount() == null ? 0L : a.getCount());
        row.put("averageDurationMs", a.getAverageDurationMs() == null ? 0.0 : a.getAverageDurationMs());
        row.put("retried", a.getRetried() == null ? 0L : a.getRetried());
        return row;
    }

    private static String safe(String value, int max) {
        if (value == null || value.isBlank()) return "unknown";
        String v = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
