package com.example.travel.observability;

import com.example.travel.entity.AgentGoalEvaluation;
import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.repository.AgentGoalEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable, user-scoped evaluation history backed by PostgreSQL/JPA. */
@Service
public class AgentEvaluationLedger {
    private static final Logger log = LoggerFactory.getLogger(AgentEvaluationLedger.class);
    private final AgentGoalEvaluationRepository repository;
    private final ObjectMapper objectMapper;

    public AgentEvaluationLedger(AgentGoalEvaluationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Run outside any caller transaction so a telemetry write failure cannot mark the
     * travel-planning transaction rollback-only. Repository writes use their own transaction.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void record(TravelState state, GoalEvaluation evaluation) {
        if (evaluation == null) return;
        String userId = safe(state == null ? null : state.userId(), 120);
        if (userId.isBlank()) {
            log.warn("Skipping goal evaluation persistence because authenticated user id is absent");
            return;
        }
        AgentGoalEvaluation row = new AgentGoalEvaluation();
        row.setUserId(userId);
        String threadId = safe(state == null ? null : state.graphThreadId(), 120);
        row.setThreadId(threadId.isBlank() ? "untracked" : threadId);
        row.setDestination(safe(state == null ? null : state.destination(), 120));
        row.setBudget(safe(state == null ? null : state.budgetLabel(), 100));
        String goal = "";
        if (state != null && state.agentPlan() != null) goal = safe(state.agentPlan().getGoal(), 200);
        row.setGoal(goal);
        GoalEvaluation.Status status = evaluation.getStatus();
        if (status == null) {
            log.warn("Skipping goal evaluation persistence because status is absent");
            return;
        }
        row.setStatus(status.name());
        double score = evaluation.getScore();
        row.setScore(Double.isFinite(score) ? score : 0.0);
        row.setReason(safe(evaluation.getReason(), 500));
        row.setRecoverable(evaluation.isRecoverable());
        row.setCreatedAt(Instant.now());
        row.setSatisfiedCriteriaJson(writeJson(evaluation.getSatisfiedCriteria()));
        row.setUnmetCriteriaJson(writeJson(evaluation.getUnmetCriteria()));
        row.setBlockingIssuesJson(writeJson(evaluation.getBlockingIssues()));
        try {
            repository.save(row);
        } catch (RuntimeException ex) {
            // Evaluation history is observability; a DB outage must not fail trip execution.
            log.warn("Could not persist goal evaluation telemetry; continuing without durable event ({})",
                    ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public List<EvaluationRecord> recent(String userId, int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        if (userId == null || userId.isBlank()) return List.of();
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, bounded))
                .stream().map(this::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public Page<EvaluationRecord> search(String userId, int days, int page, int pageSize, String status, String query, String sortBy, String direction) {
        if (userId == null || userId.isBlank()) return Page.empty();
        int safeDays = Math.max(0, Math.min(3650, days));
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        Instant since = safeDays == 0 ? null : Instant.now().minusSeconds(safeDays * 86400L);
        String safeStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (safeStatus != null && !List.of("ACHIEVED", "PARTIAL", "FAILED", "NEEDS_USER").contains(safeStatus)) {
            safeStatus = null;
        }
        String safeQuery = query == null || query.isBlank() ? null : "%" + query.trim().substring(0, Math.min(120, query.trim().length())) + "%";
        String sortProperty = switch (sortBy == null ? "" : sortBy.trim()) {
            case "score" -> "score";
            case "status" -> "status";
            case "destination" -> "destination";
            default -> "createdAt";
        };
        Sort.Direction sortDirection = "ASC".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sortProperty));
        // Build predicates only for supplied filters. Optional parameters in JPQL such as
        // (:since is null or createdAt >= :since) are unsafe with PostgreSQL/Hibernate because
        // the null-only bind has no inferable SQL type (SQLState 42P18).
        final String textPattern = safeQuery;
        Specification<AgentGoalEvaluation> specification = (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
            if (since != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), since));
            }
            if (safeStatus != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), safeStatus));
            }
            if (textPattern != null) {
                String pattern = textPattern.toLowerCase(java.util.Locale.ROOT);
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("goal"), "")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("destination"), "")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("threadId"), "")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("reason"), "")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("budget"), "")), pattern)));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(specification, pageable).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public Map<String, String> labelsForThreads(String userId, java.util.Collection<String> threadIds) {
        if (userId == null || userId.isBlank() || threadIds == null || threadIds.isEmpty()) return Map.of();
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (AgentGoalEvaluation row : repository.findByUserIdAndThreadIdInOrderByCreatedAtDesc(userId, threadIds)) {
            String label = !safe(row.getDestination(), 120).isBlank() ? safe(row.getDestination(), 120)
                    : !safe(row.getGoal(), 200).isBlank() ? safe(row.getGoal(), 200) : "Trip";
            if (!safe(row.getGoal(), 200).isBlank() && !label.equals(safe(row.getGoal(), 200))) {
                label += " · " + safe(row.getGoal(), 200);
            }
            labels.putIfAbsent(row.getThreadId(), label);
        }
        return Map.copyOf(labels);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String userId, int days) {
        if (userId == null || userId.isBlank()) return Map.of(
                "evaluations", 0L, "achieved", 0L, "partial", 0L, "failed", 0L,
                "needsUser", 0L, "averageScore", 0.0);
        int safeDays = Math.max(0, Math.min(3650, days));
        if (safeDays == 0) return summary(userId);
        Instant since = Instant.now().minusSeconds(safeDays * 86400L);
        long total = repository.countByUserIdAndCreatedAtAfter(userId, since);
        return Map.of(
                "evaluations", total,
                "achieved", repository.countByUserIdAndStatusAndCreatedAtAfter(userId, "ACHIEVED", since),
                "partial", repository.countByUserIdAndStatusAndCreatedAtAfter(userId, "PARTIAL", since),
                "failed", repository.countByUserIdAndStatusAndCreatedAtAfter(userId, "FAILED", since),
                "needsUser", repository.countByUserIdAndStatusAndCreatedAtAfter(userId, "NEEDS_USER", since),
                "averageScore", Math.round(repository.averageScoreByUserIdAndCreatedAtAfter(userId, since) * 1000.0) / 1000.0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of("evaluations", 0L, "achieved", 0L, "partial", 0L,
                    "failed", 0L, "needsUser", 0L, "averageScore", 0.0,
                    "retention", "persistent PostgreSQL history");
        }
        double average = repository.averageScoreByUserId(userId);
        return Map.of(
                "evaluations", repository.countByUserId(userId),
                "achieved", repository.countByUserIdAndStatus(userId, "ACHIEVED"),
                "partial", repository.countByUserIdAndStatus(userId, "PARTIAL"),
                "failed", repository.countByUserIdAndStatus(userId, "FAILED"),
                "needsUser", repository.countByUserIdAndStatus(userId, "NEEDS_USER"),
                "averageScore", Math.round(average * 1000.0) / 1000.0,
                "retention", "persistent PostgreSQL history");
    }

    private EvaluationRecord toRecord(AgentGoalEvaluation row) {
        Instant createdAt = row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt();
        return new EvaluationRecord(createdAt.toString(), row.getThreadId(), row.getGoal(),
                row.getDestination(), row.getBudget(), row.getStatus(), row.getScore(), row.getReason(),
                readJson(row.getSatisfiedCriteriaJson()), readJson(row.getUnmetCriteriaJson()),
                readJson(row.getBlockingIssuesJson()), row.isRecoverable());
    }

    private String writeJson(List<String> values) {
        try { return objectMapper.writeValueAsString(values == null ? List.of() : values); }
        catch (Exception ex) { log.warn("Could not serialize evaluation criteria", ex); return "[]"; }
    }

    private List<String> readJson(String value) {
        try {
            Object parsed = objectMapper.readValue(value == null ? "[]" : value, List.class);
            if (!(parsed instanceof List<?> list)) return List.of();
            List<String> result = new ArrayList<>();
            for (Object item : list) if (item != null) result.add(String.valueOf(item));
            return List.copyOf(result);
        } catch (Exception ex) {
            log.warn("Could not deserialize persisted evaluation criteria", ex);
            return List.of();
        }
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 1) + "…";
    }

    public record EvaluationRecord(String timestamp, String threadId, String goal, String destination,
            String budget, String status, double score, String reason, List<String> satisfiedCriteria,
            List<String> unmetCriteria, List<String> blockingIssues, boolean recoverable) {}
}
