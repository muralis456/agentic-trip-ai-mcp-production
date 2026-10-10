package com.example.travel.observability;

import com.example.travel.entity.AgentModelUsageEvent;
import com.example.travel.repository.AgentModelUsageEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Durable per-user model telemetry. Never persists prompts, completions, or tool arguments. */
@Service
public class AgentModelUsageLedger {
    private static final Logger log = LoggerFactory.getLogger(AgentModelUsageLedger.class);
    private final AgentModelUsageEventRepository repository;

    public AgentModelUsageLedger(AgentModelUsageEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String userId, String threadId, String model, String operation,
                       boolean success, int inputTokens, int outputTokens, long durationMs) {
        if (userId == null || userId.isBlank()) {
            // Do not attribute calls to an arbitrary user when run context is missing.
            return;
        }
        try {
            repository.save(new AgentModelUsageEvent(
                    safe(userId, 120), safeNonBlank(threadId, "untracked", 160),
                    safeNonBlank(model, "unknown", 120), safeNonBlank(operation, "unknown", 40),
                    success ? "SUCCESS" : "ERROR", Math.max(0, inputTokens),
                    Math.max(0, outputTokens), Math.max(0, durationMs), Instant.now()));
        } catch (RuntimeException ex) {
            // Telemetry persistence must never break travel planning.
            log.warn("Could not persist model usage telemetry; continuing without durable event ({})",
                    ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public List<AgentModelUsageEvent> recentForUser(String userId, int days, int limit) {
        if (userId == null || userId.isBlank()) return List.of();
        int safeDays = Math.max(1, Math.min(365, days));
        int safeLimit = Math.max(1, Math.min(5000, limit));
        return repository.findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                userId, Instant.now().minusSeconds(safeDays * 86400L), PageRequest.of(0, safeLimit));
    }

    private static String safeNonBlank(String value, String fallback, int max) {
        String safe = safe(value, max);
        return safe.isBlank() ? fallback : safe;
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}