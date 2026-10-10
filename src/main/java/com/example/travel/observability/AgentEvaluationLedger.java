package com.example.travel.observability;

import com.example.travel.model.GoalEvaluation;
import com.example.travel.graph.TravelState;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Process-local recent evaluation ledger for the authenticated observability UI.
 * It intentionally stores bounded, structured summaries rather than full prompts
 * or tool arguments. Durable long-term analytics should use a persistent store.
 */
@Service
public class AgentEvaluationLedger {
    private static final int MAX_EVENTS = 200;
    private final Deque<EvaluationRecord> records = new ArrayDeque<>();

    public synchronized void record(TravelState state, GoalEvaluation evaluation) {
        if (evaluation == null) return;
        String threadId = safe(state == null ? null : state.graphThreadId(), 100);
        String destination = safe(state == null ? null : state.destination(), 100);
        String budget = safe(state == null ? null : state.budgetLabel(), 80);
        String goal = "";
        if (state != null && state.agentPlan() != null) {
            goal = safe(state.agentPlan().getGoal(), 180);
        }
        EvaluationRecord record = new EvaluationRecord(
                Instant.now().toString(),
                threadId.isBlank() ? "untracked" : threadId,
                goal,
                destination,
                budget,
                evaluation.getStatus().name(),
                evaluation.getScore(),
                safe(evaluation.getReason(), 400),
                List.copyOf(evaluation.getSatisfiedCriteria()),
                List.copyOf(evaluation.getUnmetCriteria()),
                List.copyOf(evaluation.getBlockingIssues()),
                evaluation.isRecoverable());
        records.addFirst(record);
        while (records.size() > MAX_EVENTS) records.removeLast();
    }

    public synchronized List<EvaluationRecord> recent(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        List<EvaluationRecord> result = new ArrayList<>(Math.min(bounded, records.size()));
        for (EvaluationRecord record : records) {
            if (result.size() >= bounded) break;
            result.add(record);
        }
        return List.copyOf(result);
    }

    public synchronized Map<String, Object> summary() {
        long achieved = records.stream().filter(r -> "ACHIEVED".equals(r.status())).count();
        long partial = records.stream().filter(r -> "PARTIAL".equals(r.status())).count();
        long failed = records.stream().filter(r -> "FAILED".equals(r.status())).count();
        long needsUser = records.stream().filter(r -> "NEEDS_USER".equals(r.status())).count();
        double averageScore = records.stream().mapToDouble(EvaluationRecord::score).average().orElse(0.0);
        return Map.of(
                "evaluations", records.size(),
                "achieved", achieved,
                "partial", partial,
                "failed", failed,
                "needsUser", needsUser,
                "averageScore", Math.round(averageScore * 1000.0) / 1000.0,
                "retention", "process-local; recent evaluations since application startup");
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 1) + "…";
    }

    public record EvaluationRecord(
            String timestamp,
            String threadId,
            String goal,
            String destination,
            String budget,
            String status,
            double score,
            String reason,
            List<String> satisfiedCriteria,
            List<String> unmetCriteria,
            List<String> blockingIssues,
            boolean recoverable) {}
}
