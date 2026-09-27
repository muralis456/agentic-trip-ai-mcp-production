package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.NodeFailureRouting;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.AgentDecision;
import com.example.travel.model.NodeFailureInfo;
import com.example.travel.model.ReplanAction;
import com.example.travel.model.ReplanStrategy;
import com.example.travel.model.SupervisorAssessment;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hybrid supervisor: deterministic safety rules plus LLM quality assessment.
 */
@Service
public class SupervisorAgentService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgentService.class);

    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;

    public SupervisorAgentService(RoutedLlm routedLlm, JsonSupport jsonSupport) {
        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
    }

    public Map<String, Object> review(TravelState state) {
        SupervisorAssessment assessment = deterministicAssessment(state);
        String decision = decide(state, assessment);

        AgentDecision recorded = new AgentDecision("supervisor", decision,
                assessment.getReason().isBlank()
                        ? buildDeterministicReason(state)
                        : assessment.getReason(),
                assessment.getQualityHint());

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.LAST_DECISION, recorded);
        updates.put(TravelState.SUPERVISOR_DECISION, decision);
        updates.put(TravelState.SUPERVISOR_ASSESSMENT, assessment);
        if (assessment.getRecommendedAction() != null && decision.equals(TravelGraphNodes.ROUTE_RETRY)) {
            ReplanStrategy strategy = new ReplanStrategy();
            strategy.setActions(List.of(assessment.getRecommendedAction().wireName()));
            strategy.setReason(assessment.getReason());
            updates.put(TravelState.REPLAN_STRATEGY, strategy);
        }
        GraphExecutionLogger.supervisorDecision(state, decision, assessment.getQualityHint(), recorded.getReason());
        return updates;
    }

    private SupervisorAssessment deterministicAssessment(TravelState state) {
        SupervisorAssessment assessment = new SupervisorAssessment();
        assessment.setDecision(TravelGraphNodes.ROUTE_PROCEED);
        assessment.setQualityHint(0.90);
        assessment.setConfidence(1.0);
        assessment.setReason("Deterministic specialist checks passed");

        // If the current pass has an explicit failure, keep the existing LLM
        // assessment path for unusual recovery decisions. Normal successful
        // specialist fan-out should never pay for another local-LLM call.
        if (NodeFailureRouting.hasRetryableFailure(state)) {
            return llmAssess(state);
        }
        if (state.runFlights() && flightsUnusable(state)) return llmAssess(state);
        if (state.runHotels() && state.hotels().isEmpty() && !state.hotelFallbackExhausted()) return llmAssess(state);
        if (state.runResearch() && state.research().isEmpty() && state.attractions().isEmpty()) return llmAssess(state);
        if (state.runWeather() && (state.weather() == null
                || (TravelState.isBlank(state.weather().getSummary())
                && (state.weather().getDays() == null || state.weather().getDays().isEmpty())))) return llmAssess(state);
        return assessment;
    }

    public String decide(TravelState state) {
        return decide(state, state.supervisorAssessment());
    }

    private String decide(TravelState state, SupervisorAssessment assessment) {

        if (state.retryCount() >= state.maxRetries()) {
            return TravelGraphNodes.ROUTE_PROCEED;
        }

        // Real node failure always gets priority.
        if (NodeFailureRouting.hasRetryableFailure(state)) {
            NodeFailureInfo failure = state.nodeFailure();

            log.error(
                    "Supervisor retrying after node failure on {} error={}",
                    failure.getLastFailedNode(),
                    failure.getLastError());

            return TravelGraphNodes.ROUTE_RETRY;
        }

        // A provider failure explicitly classified as non-retryable (for example
        // AviationStack HTTP 429 quota/rate-limit exhaustion) must not be turned
        // back into a retry just because the specialist result is empty. The
        // specialist has already recorded the authoritative failure reason.
        NodeFailureInfo nonRetryableFailure = state.nodeFailure();
        if (nonRetryableFailure != null
                && !TravelState.isBlank(nonRetryableFailure.getLastFailedNode())
                && !nonRetryableFailure.isRetryable()) {
            log.warn("Supervisor: non-retryable specialist failure node={} error={}; proceeding without retry",
                    nonRetryableFailure.getLastFailedNode(), nonRetryableFailure.getLastError());
            return TravelGraphNodes.ROUTE_PROCEED;
        }

        // Validate ONLY specialists requested in the current graph pass.
        // NEEDS_* is historical/cumulative and must never drive retry routing.
        //
        // Compatibility rule for direct/unit-test states: if no specialist
        // RUN flag exists at all, an explicit NEEDS_* flag represents the
        // requested work for that isolated state. In a real graph pass at
        // least one RUN flag is present, so cumulative NEEDS_* cannot cause
        // an old specialist to be retried.
        boolean hasCurrentRun = state.runFlights()
                || state.runHotels()
                || state.runResearch()
                || state.runWeather();

        if (state.runFlights() && flightsUnusable(state)) {
            log.warn("Supervisor: current flight run has no usable result");
            return TravelGraphNodes.ROUTE_RETRY;
        }

        if (state.runHotels() && state.hotels().isEmpty()) {
            if (state.hotelFallbackExhausted()) {
                log.warn("Supervisor: hotel provider and independent fallback exhausted; not retrying MCP hotel call");
            } else {
                log.warn("Supervisor: current hotel run has no result");
                return TravelGraphNodes.ROUTE_RETRY;
            }
        }

        if (state.runResearch()
                && state.research().isEmpty()
                && state.attractions().isEmpty()) {
            log.warn("Supervisor: current research run has no result");
            return TravelGraphNodes.ROUTE_RETRY;
        }

        if (state.runWeather()
                && (state.weather() == null
                || (TravelState.isBlank(state.weather().getSummary())
                    && (state.weather().getDays() == null || state.weather().getDays().isEmpty())))) {
            log.warn("Supervisor: current weather run has no result");
            return TravelGraphNodes.ROUTE_RETRY;
        }

        // Backward-compatible isolated-state check. This path is intentionally
        // disabled whenever a current RUN_* specialist exists.
        if (!hasCurrentRun && state.needsFlights() && flightsUnusable(state)) {
            log.warn("Supervisor: isolated state requires flights but has no usable result");
            return TravelGraphNodes.ROUTE_RETRY;
        }

        // Budget violation is a genuine reason to replan.
        if (!state.runBudget() && state.shouldReplanForBudget()) {
            assessment.setDecision("REPLAN");
            assessment.setReason("Hotel and flight costs exceed budget ceiling");
            assessment.setRecommendedAction(ReplanAction.REDUCE_HOTEL_BUDGET);

            return TravelGraphNodes.ROUTE_RETRY;
        }

        /*
         * At this point all deterministic requirements have passed.
         *
         * Do NOT retry merely because the small LLM returned a low
         * qualityHint. The LLM can produce an unreliable quality score,
         * especially when the specialist outputs are already valid.
         */
        String llmDecision = assessment.getDecision() == null
                ? ""
                : assessment.getDecision().toUpperCase(Locale.ROOT);

        if ("REPLAN".equals(llmDecision)
                && assessment.getRecommendedAction() != null) {

            log.info(
                    "Supervisor LLM suggested replan, but current specialist checks passed. "
                            + "Proceeding to downstream budget/itinerary stages: action={} reason={}",
                    assessment.getRecommendedAction(),
                    assessment.getReason());
        }

        /*
         * RETRY from the LLM is accepted only when there is an actual
         * deterministic problem. We already checked those above.
         *
         * Therefore do not retry solely because:
         * qualityHint < 0.70
         * confidence >= 0.75
         */
        if ("RETRY".equals(llmDecision)) {
            log.info(
                    "Supervisor LLM requested RETRY, but deterministic checks passed. "
                            + "Proceeding to avoid unnecessary replan. qualityHint={} confidence={}",
                    assessment.getQualityHint(),
                    assessment.getConfidence());

            return TravelGraphNodes.ROUTE_PROCEED;
        }

        return TravelGraphNodes.ROUTE_PROCEED;
    }

    private SupervisorAssessment llmAssess(TravelState state) {
        SupervisorAssessment fallback = new SupervisorAssessment();
        fallback.setDecision(TravelGraphNodes.ROUTE_PROCEED);
        fallback.setQualityHint(0.85);
        fallback.setConfidence(0.75);
        try {
            String content = routedLlm.complete(AgentRole.EXTRACT,
                    "You are the travel plan supervisor. Return JSON only: "
                            + "{\"decision\":\"PROCEED|RETRY|REPLAN\",\"reason\":\"\","
                            + "\"qualityHint\":0.0,\"confidence\":0.0,"
                            + "\"suggestedStrategy\":\"reduce_hotel_budget|cheaper_flight|adjust_itinerary|\"}. "
                            + "Evaluate the actual specialist results. "
                            + "Return PROCEED when required specialist data is present and usable. "
                            + "Return RETRY only when a required specialist failed or its result is unusable. "
                            + "Return REPLAN only when the current plan cannot satisfy the user's request "
                            + "or requires a strategy change. "
                            + "Do not return RETRY merely because qualityHint is low. "
                            + "Do not return RETRY merely because the itinerary is incomplete when "
                            + "the itinerary was not requested or required.",
                    "User request: " + state.userRequest()
                            + "\nDestination: " + state.destination()
                            + "\nCurrent RUN flights: " + state.runFlights()
                            + "\nCurrent RUN hotels: " + state.runHotels()
                            + "\nCurrent RUN research: " + state.runResearch()
                            + "\nCurrent RUN weather: " + state.runWeather()
                            + "\nCurrent RUN budget: " + state.runBudget()
                            + "\nCurrent RUN itinerary: " + state.runItinerary()
                            + "\nFlights result count: " + state.flights().size()
                            + "\nHotels result count: " + state.hotels().size()
                            + "\nResearch topics: " + state.research().size()
                            + "\nBudget within ceiling: "
                            + (state.budgetSummary() == null || state.budgetSummary().isWithinBudget())
                            + "\nWeather: " + (state.weather() == null ? "n/a" : state.weather().toDisplay())
                            + "\nItinerary days: "
                            + (state.itinerary() == null ? 0 : state.itinerary().getDays().size()));
            return jsonSupport.readTree(content).map(this::parseAssessment).orElse(fallback);
        } catch (Exception ex) {
            if (ex instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.debug("Supervisor LLM assessment skipped: {}", ex.getMessage());
            return fallback;
        }
    }

    private SupervisorAssessment parseAssessment(JsonNode tree) {
        SupervisorAssessment assessment = new SupervisorAssessment();
        if (tree.hasNonNull("decision")) {
            assessment.setDecision(tree.get("decision").asString());
        }
        if (tree.hasNonNull("reason")) {
            assessment.setReason(tree.get("reason").asString());
        }
        if (tree.has("qualityHint")) {
            assessment.setQualityHint(tree.get("qualityHint").asDouble());
        }
        if (tree.has("confidence")) {
            assessment.setConfidence(tree.get("confidence").asDouble());
        }
        if (tree.hasNonNull("suggestedStrategy")) {
            assessment.setSuggestedStrategy(tree.get("suggestedStrategy").asString());
            ReplanAction.fromToken(assessment.getSuggestedStrategy()).ifPresent(assessment::setRecommendedAction);
        }
        return assessment;
    }

    private String buildDeterministicReason(TravelState state) {
        return "runFlights=" + state.runFlights()
                + " runHotels=" + state.runHotels()
                + " runResearch=" + state.runResearch()
                + " runWeather=" + state.runWeather()
                + " runBudget=" + state.runBudget()
                + " runItinerary=" + state.runItinerary();
    }

    private boolean flightsUnusable(TravelState state) {
        if (state.flights().isEmpty()) {
            return true;
        }
        return state.flights().stream()
                .allMatch(flight -> flight == null || "unavailable".equalsIgnoreCase(flight.getStatus()));
    }
}
