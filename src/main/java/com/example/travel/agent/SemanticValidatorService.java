package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.TravelState;
import com.example.travel.model.ReplanAction;
import com.example.travel.model.SemanticValidationResult;
import com.example.travel.model.TripRequirements;
import com.example.travel.model.ValidationStatus;
import com.example.travel.service.RequirementEvaluator;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM judge for preference fit with structured JSON output.
 */
@Service
public class SemanticValidatorService {

    private static final Logger log = LoggerFactory.getLogger(SemanticValidatorService.class);

    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;
    private final RequirementEvaluator requirementEvaluator;

    public SemanticValidatorService(RoutedLlm routedLlm,
                                    JsonSupport jsonSupport,
                                    RequirementEvaluator requirementEvaluator) {
        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
        this.requirementEvaluator = requirementEvaluator;
    }

    public SemanticValidationResult review(TravelState state) {
        SemanticValidationResult result = new SemanticValidationResult();
        result.setStatus(ValidationStatus.PASS);
        result.setScore(1.0);
        if (!state.needsItinerary() || state.itinerary() == null || state.itinerary().isEmpty()) {
            return result;
        }

        TripRequirements requirements = state.tripRequirements();
        result.getIssues().addAll(requirementEvaluator.evaluateIssues(state, requirements));

        // Deterministic requirement scoring is authoritative for structured
        // preferences. If it passes, do not spend another local-LLM call merely
        // to confirm the same facts. The previous validator call added ~6s and
        // could incorrectly trigger a full itinerary replan.
        if (result.getIssues().isEmpty()) {
            GraphExecutionLogger.semanticValidation(state, "PASS", result.getScore(), result.getIssues());
            return result;
        }

        try {
            String content = routedLlm.complete(AgentRole.EXTRACT,
                    "You are a semantic travel validator. Return JSON only: "
                            + "{\"status\":\"PASS|FAIL|WARN\",\"score\":0.0,"
                            + "\"issues\":[\"\"],\"recommendedActions\":[\"adjust_itinerary\"]}. "
                            + "Score is 0-1 plan quality for preferences. "
                            + "recommendedActions must be from: reduce_hotel_budget, cheaper_flight, "
                            + "remove_expensive_attractions, hotel_upgrade, adjust_itinerary, add_destination. "
                            + "Use ONLY facts present in the request/state. Do not infer a party size from "
                            + "family-friendly; family-friendly is a preference. The authoritative traveler count is "
                            + "the Travelers value below. Do not claim that a budget is insufficient when the computed "
                            + "budget is within the user's ceiling. Do not invent a recommended budget or family size. "
                            + "Only report missing food/family/local preferences when the supplied itinerary genuinely lacks them.",
                    "Request: " + state.userRequest()
                            + "\nTravelers: " + state.travelers()
                            + "\nStyle: " + state.travelStyle()
                            + "\nRequested budget ceiling: " + state.budget()
                            + "\nComputed budget within ceiling: " + (state.budgetSummary() == null || state.budgetSummary().isWithinBudget())
                            + "\nComputed budget assessment: " + (state.budgetSummary() == null ? "n/a" : state.budgetSummary().getAssessment())
                            + "\nItinerary:\n" + state.itinerary().toDisplay());
            jsonSupport.readTree(content).ifPresent(tree -> mergeLlmResult(result, tree));
        } catch (Exception exception) {
            if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Semantic validator LLM failed; using structured heuristics only", exception);
            if (!result.getIssues().isEmpty()) {
                result.setStatus(ValidationStatus.WARN);
                result.setScore(0.72);
            }
        }

        if (!result.getIssues().isEmpty() && result.getScore() >= 0.8) {
            result.setScore(Math.min(result.getScore(), 0.72));
        }
        if (result.failed() && result.getStatus() != ValidationStatus.FAIL) {
            result.setStatus(ValidationStatus.FAIL);
        }
        if (result.getRecommendedActions().isEmpty() && result.failed()) {
            result.getRecommendedActions().add(ReplanAction.ADJUST_ITINERARY);
        }
        GraphExecutionLogger.semanticValidation(state,
                result.getStatus() == null ? "PASS" : result.getStatus().name(),
                result.getScore(),
                result.getIssues());
        return result;
    }

    public List<String> issueNotes(SemanticValidationResult result) {
        return result == null ? List.of() : new ArrayList<>(result.getIssues());
    }

    private void mergeLlmResult(SemanticValidationResult result, JsonNode tree) {
        if (tree.hasNonNull("status")) {
            try {
                result.setStatus(ValidationStatus.valueOf(tree.get("status").asString().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // keep existing status
            }
        }
        if (tree.has("score")) {
            result.setScore(tree.get("score").asDouble());
        }
        if (tree.has("issues") && tree.get("issues").isArray()) {
            for (JsonNode issue : tree.get("issues")) {
                String text = issue.asString("");
                if (!TravelState.isBlank(text) && !result.getIssues().contains(text)) {
                    result.getIssues().add(text);
                }
            }
        }
        if (tree.has("recommendedActions") && tree.get("recommendedActions").isArray()) {
            List<ReplanAction> actions = new ArrayList<>();
            for (JsonNode token : tree.get("recommendedActions")) {
                ReplanAction.fromToken(token.asString("")).ifPresent(action -> {
                    if (!actions.contains(action)) {
                        actions.add(action);
                    }
                });
            }
            result.setRecommendedActions(actions);
        }
    }
}
