package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import com.example.travel.model.GoalEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevReplanDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevReplanDecisionService.class);
    private final JevDecisionService decisions;

    public JevReplanDecisionService(JevDecisionService decisions) {
        this.decisions = decisions;
    }

    public Decision choose(TravelState state, GoalEvaluation evaluation, List<String> proposed) {
        log.info("jev.replan.decision-start proposedActions={} unmetCriteria={}",
                proposed == null ? 0 : proposed.size(),
                evaluation == null || evaluation.getUnmetCriteria() == null ? 0 : evaluation.getUnmetCriteria().size());

        List<String> allowed = List.of(
                "FLIGHT", "HOTEL", "BUDGET", "ITINERARY", "RESEARCH", "WEATHER", "ASK_USER", "NONE");
        String fallback = deterministic(evaluation, state);

        try {
            Map<String, String> criteria = new LinkedHashMap<>();
            for (String action : allowed) {
                criteria.put(action, description(action));
            }

            var d = decisions.choose(
                    JevDecisionPolicy.DecisionKind.REPLAN,
                    Map.of(
                            "request", state.userRequest(),
                            "unmet", evaluation == null ? List.of() : evaluation.getUnmetCriteria(),
                            "blocking", evaluation == null || evaluation.getBlockingIssues() == null
                                    ? List.of() : evaluation.getBlockingIssues(),
                            "proposedActions", proposed == null ? List.of() : proposed),
                    "Choose the single highest-value replan lever. Select only a capability that can address the unmet outcome. Do not choose ASK_USER unless automation cannot safely recover; choose NONE only when no replan is needed.",
                    criteria);

            String choice = d.choice().toUpperCase();
            Decision result = d.accepted() && allowed.contains(choice)
                    ? new Decision(choice, d.confidence(), true, "jev-policy-accepted")
                    : new Decision(fallback, d.confidence(), false, "deterministic-replan-fallback");

            log.info("jev.replan.decision outcome={} recommendation={} confidence={} accepted={} reason={} model={}",
                    result.action(), choice, result.confidence(), result.accepted(), result.reason(), d.model());
            return result;
        } catch (Exception ex) {
            log.warn("jev.replan.decision-fallback outcome={} reason={}",
                    fallback, ex.getClass().getSimpleName());
            return new Decision(fallback, 0, false, "jev unavailable: " + ex.getClass().getSimpleName());
        }
    }

    private String deterministic(GoalEvaluation evaluation, TravelState state) {
        String unmet = String.join(
                " ",
                evaluation == null ? List.of() : evaluation.getUnmetCriteria()).toLowerCase();

        if (unmet.contains("flight")) return "FLIGHT";
        if (unmet.contains("hotel")) return "HOTEL";
        if (unmet.contains("budget") || state.overBudget()) return "BUDGET";
        if (unmet.contains("itinerary")) return "ITINERARY";
        if (unmet.contains("weather")) return "WEATHER";
        if (unmet.contains("research") || unmet.contains("knowledge")) return "RESEARCH";
        return "ASK_USER";
    }

    private String description(String action) {
        return switch (action) {
            case "FLIGHT" -> "Change flight search/provider/date/price strategy.";
            case "HOTEL" -> "Change hotel search, budget, or accommodation strategy.";
            case "BUDGET" -> "Reduce total cost using configurable travel levers.";
            case "ITINERARY" -> "Regenerate itinerary using the new evidence.";
            case "RESEARCH" -> "Acquire missing destination/research evidence.";
            case "WEATHER" -> "Acquire or refine weather evidence.";
            case "ASK_USER" -> "Ask the user because automation cannot safely resolve the blocker.";
            default -> "No replan is required.";
        };
    }

    public record Decision(String action, double confidence, boolean accepted, String reason) { }
}
