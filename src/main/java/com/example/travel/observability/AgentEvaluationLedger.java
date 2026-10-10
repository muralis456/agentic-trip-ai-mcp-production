package com.example.travel.observability;

import com.example.travel.entity.AgentGoalEvaluation;
import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.repository.AgentGoalEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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

    @Transactional
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
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public List<EvaluationRecord> recent(String userId, int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        if (userId == null || userId.isBlank()) return List.of();
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, bounded))
                .stream().map(this::toRecord).toList();
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
