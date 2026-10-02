package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevHitlDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevHitlDecisionService.class);
    private final JevDecisionService decisions;

    public JevHitlDecisionService(JevDecisionService decisions) {
        this.decisions = decisions;
    }

    public Decision decide(TravelState state) {
        log.info("jev.hitl.decision-start userInputRequired={} validationErrors={} goalStatus={}",
                state.userInputRequired(),
                state.validationErrors().size(),
                state.goalEvaluation() == null ? "UNKNOWN" : state.goalEvaluation().getStatus());

        // Hard safety gates always win over JEV. JEV cannot override unresolved
        // validation, missing user input, or an unmet goal.
        if (state.userInputRequired()
                || !state.validationErrors().isEmpty()
                || (state.goalEvaluation() != null
                && state.goalEvaluation().getStatus() != com.example.travel.model.GoalEvaluation.Status.ACHIEVED)) {
            return new Decision("ASK_USER", 1, true, "hard policy requires user review");
        }

        try {
            var d = decisions.choose(
                    JevDecisionPolicy.DecisionKind.HITL,
                    Map.of(
                            "request", state.userRequest(),
                            "tripPlanning", state.isTripPlanningWorkflow(),
                            "goalStatus", state.goalEvaluation() == null
                                    ? "UNKNOWN" : state.goalEvaluation().getStatus().name(),
                            "validationErrors", state.validationErrors()),
                    "Decide whether this completed result can be returned automatically or should pause for user review. Never auto-complete when validation is unresolved or the goal is not achieved.",
                    Map.of(
                            "AUTO_COMPLETE", "All deterministic checks pass and the result can be returned without user confirmation.",
                            "ASK_USER", "User review is required or material ambiguity remains."));

            String choice = d.choice().toUpperCase();
            boolean valid = choice.equals("AUTO_COMPLETE") || choice.equals("ASK_USER");
            Decision result = d.accepted() && valid
                    ? new Decision(choice, d.confidence(), true, "jev-policy-accepted")
                    : new Decision("ASK_USER", d.confidence(), false, "deterministic-hitl-fallback");

            log.info("jev.hitl.decision outcome={} recommendation={} confidence={} accepted={} reason={} model={}",
                    result.route(), choice, result.confidence(), result.accepted(), result.reason(), d.model());
            return result;
        } catch (Exception ex) {
            log.warn("jev.hitl.decision-fallback outcome=ASK_USER reason={}",
                    ex.getClass().getSimpleName());
            return new Decision("ASK_USER", 0, false, "jev unavailable: " + ex.getClass().getSimpleName());
        }
    }

    public record Decision(String route, double confidence, boolean accepted, String reason) { }
}
