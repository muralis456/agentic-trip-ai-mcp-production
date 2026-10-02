package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevGoalDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevGoalDecisionService.class);
    private final JevDecisionService decisions;

    public JevGoalDecisionService(JevDecisionService decisions) {
        this.decisions = decisions;
    }

    public Decision decide(TravelState state, GoalEvaluation evaluation) {
        log.info("jev.goal.decision-start status={} recoverable={}",
                evaluation == null ? "MISSING" : evaluation.getStatus(),
                evaluation != null && evaluation.isRecoverable());

        if (evaluation == null) {
            return new Decision("HITL", 0, false, "missing evaluation");
        }
        if (evaluation.getStatus() == GoalEvaluation.Status.ACHIEVED) {
            return new Decision("ACHIEVED", 1, true, "deterministic goal contract satisfied");
        }
        if (evaluation.getStatus() == GoalEvaluation.Status.NEEDS_USER) {
            return new Decision("HITL", 1, true, "user input is required");
        }

        String fallback = evaluation.isRecoverable() ? "REPLAN" : "HITL";
        try {
            var d = decisions.choose(
                    JevDecisionPolicy.DecisionKind.GOAL,
                    Map.of(
                            "goal", state.agentPlan() == null ? "" : state.agentPlan().getGoal(),
                            "status", evaluation.getStatus().name(),
                            "reason", evaluation.getReason() == null ? "" : evaluation.getReason(),
                            "unmet", evaluation.getUnmetCriteria() == null ? java.util.List.of() : evaluation.getUnmetCriteria(),
                            "blocking", evaluation.getBlockingIssues() == null ? java.util.List.of() : evaluation.getBlockingIssues()),
                    "Choose the next lifecycle route. Do not claim success when required outcomes are missing.",
                    Map.of(
                            "REPLAN", "Missing outcomes are recoverable through a changed execution strategy.",
                            "HITL", "Automation cannot safely recover; ask the user for a decision or clarification.",
                            "ACHIEVED", "All required outcomes are already satisfied."));

            String choice = d.choice().toUpperCase();
            Decision result = d.accepted()
                    && (choice.equals("REPLAN") || choice.equals("HITL"))
                    ? new Decision(choice, d.confidence(), true, "jev-policy-accepted")
                    : new Decision(fallback, d.confidence(), false, "deterministic-goal-fallback");

            log.info("jev.goal.decision outcome={} recommendation={} confidence={} accepted={} reason={} model={}",
                    result.route(), choice, result.confidence(), result.accepted(), result.reason(), d.model());
            return result;
        } catch (Exception ex) {
            log.warn("jev.goal.decision-fallback outcome={} reason={}",
                    fallback, ex.getClass().getSimpleName());
            return new Decision(fallback, 0, false, "jev unavailable: " + ex.getClass().getSimpleName());
        }
    }

    public record Decision(String route, double confidence, boolean accepted, String reason) { }
}
