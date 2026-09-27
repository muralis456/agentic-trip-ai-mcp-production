package com.example.travel.eval;

import com.example.travel.agent.ModificationAgentService;
import com.example.travel.agent.ReplanStrategyExecutor;
import com.example.travel.agent.SupervisorAgentService;
import com.example.travel.graph.NodeFailureRouting;
import com.example.travel.graph.SpecialistRouter;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.IntentPlan;
import com.example.travel.model.NodeFailureInfo;
import com.example.travel.model.PlanQualityScore;
import com.example.travel.agent.ValidatorAgentService;
import com.example.travel.service.ReplanActionValidator;
import com.example.travel.support.IntentClassifier;
import com.example.travel.support.ToolFailureClassifier;
import com.example.travel.tool.ToolErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight evaluation harness: routing, validation, and tool-failure policy
 * without calling Ollama or live APIs.
 */
class AgentEvaluationTest {

    @Test
    void intentRoutesFlightOnlyQueryAwayFromHotelsAndItinerary() {
        IntentPlan plan = IntentClassifier.classify("Find flights from BLR to Paris");
        assertEquals(IntentPlan.FLIGHT_SEARCH, plan.getRequestType());
        assertTrue(plan.isNeedsFlights());
        assertFalse(plan.isNeedsHotels());
        assertFalse(plan.isNeedsItinerary());
        assertFalse(plan.isNeedsResearch());
    }

    @Test
    void intentRoutesSightseeingAwayFromAviationStack() {
        IntentPlan plan = IntentClassifier.classify("What are the best places to visit in Paris?");
        assertEquals(IntentPlan.RESEARCH, plan.getRequestType());
        assertTrue(plan.isNeedsResearch());
        // A sightseeing question is research-only unless the user explicitly
        // asks for weather. This prevents unnecessary Weather MCP calls.
        assertFalse(plan.isNeedsWeather());
        assertFalse(plan.isNeedsFlights());
        assertFalse(plan.isNeedsHotels());
    }

    @Test
    void intentKeepsFullTripPlanning() {
        IntentPlan plan = IntentClassifier.classify("Plan a 10-day Japan trip under 2 lakh for a family");
        assertEquals(IntentPlan.TRIP_PLANNING, plan.getRequestType());
        assertTrue(plan.isNeedsFlights());
        assertTrue(plan.isNeedsHotels());
        assertTrue(plan.isNeedsItinerary());
    }

    @Test
    void deterministicValidatorSkipsFlightRouteWhenFlightsNotNeeded() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.DESTINATION, "Paris");
        data.put(TravelState.DEPARTURE_DATE, LocalDate.now());
        data.put(TravelState.RETURN_DATE, LocalDate.now().plusDays(4));
        data.put(TravelState.NEEDS_FLIGHTS, Boolean.FALSE);
        data.put(TravelState.NEEDS_ITINERARY, Boolean.FALSE);
        data.put(TravelState.ORIGIN_IATA, "BLR");
        data.put(TravelState.DESTINATION_IATA, "CDG");
        TravelState state = new TravelState(data);
        List<String> errors = new ValidatorAgentService().validate(state);
        assertTrue(errors.stream().noneMatch(e -> e.contains("flight")), errors::toString);
        assertTrue(errors.stream().noneMatch(e -> e.contains("Itinerary")), errors::toString);
    }

    @Test
    void intentSkipsFlightsWhenUserAlreadyBooked() {
        IntentPlan plan = IntentClassifier.classify(
                "I already booked my flight. Help me find hotels and things to do in Paris.");
        assertFalse(plan.isNeedsFlights());
        assertTrue(plan.isNeedsHotels());
        assertTrue(plan.isNeedsResearch());
        assertTrue(plan.getConfidence() >= IntentClassifier.LLM_THRESHOLD);
    }

    @Test
    void evaluationDatasetMatchesDeterministicIntent() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        try (var in = getClass().getResourceAsStream("/evaluation/travel_cases.json")) {
            var cases = mapper.readTree(in);
            for (var node : cases) {
                IntentPlan plan = IntentClassifier.classify(node.get("prompt").asString(""));
                var expected = node.get("expected");
                assertEquals(expected.get("needsFlights").asBoolean(), plan.isNeedsFlights(), plan.summary());
                assertEquals(expected.get("needsHotels").asBoolean(), plan.isNeedsHotels(), plan.summary());
                assertEquals(expected.get("needsResearch").asBoolean(), plan.isNeedsResearch(), plan.summary());
                assertEquals(expected.get("needsItinerary").asBoolean(), plan.isNeedsItinerary(), plan.summary());
            }
        }
    }

    @Test
    void semanticNotesTriggerReplan() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.VALIDATION_ERRORS, List.of());
        data.put(TravelState.SEMANTIC_NOTES, List.of("Itinerary may not be family-friendly"));
        data.put(TravelState.RETRY_COUNT, 0);
        data.put(TravelState.MAX_RETRIES, 2);
        TravelState state = new TravelState(data);
        assertTrue(state.shouldReplan());
    }

    @Test
    void hotelUpgradeDoesNotForceBudgetCostFactor() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.COST_FACTOR, java.math.BigDecimal.ONE);
        data.put(TravelState.TRAVEL_STYLE, "balanced");
        data.put(TravelState.HOTEL_CHEAPER, Boolean.FALSE);
        data.put(TravelState.FLIGHT_PREFERENCE, "balanced");
        data.put(TravelState.RETRY_COUNT, 0);
        TravelState state = new TravelState(data);
        com.example.travel.model.ReplanStrategy strategy = new com.example.travel.model.ReplanStrategy();
        strategy.setActions(List.of("hotel_upgrade"));
        strategy.setPriority("hotel");
        Map<String, Object> updates = new ReplanStrategyExecutor(new ReplanActionValidator()).apply(state, strategy);
        assertEquals("upscale", updates.get(TravelState.TRAVEL_STYLE));
        assertEquals(Boolean.FALSE, updates.get(TravelState.HOTEL_CHEAPER));
        assertEquals(java.math.BigDecimal.ONE, updates.get(TravelState.COST_FACTOR));
    }

    @Test
    void modificationHeuristicDoesNotAssumeCheaper() {
        var upgrade = com.example.travel.agent.ModificationAgentService.heuristic("I want a better hotel");
        assertEquals(com.example.travel.model.ModificationRequest.HOTEL_UPGRADE, upgrade.getChangeType());
        var cheaper = com.example.travel.agent.ModificationAgentService.heuristic("Please make it cheaper");
        assertEquals(com.example.travel.model.ModificationRequest.REDUCE_COST, cheaper.getChangeType());
    }

    @Test
    void toolFailureClassifierDoesNotRetryForbiddenOrUnprocessable() {
        assertEquals(ToolErrorCode.FORBIDDEN, ToolFailureClassifier.fromHttp(403, "function_access_restricted"));
        assertFalse(ToolFailureClassifier.fromHttp(403, "").isRetryable());
        assertEquals(ToolErrorCode.INVALID_INPUT, ToolFailureClassifier.fromHttp(422, "query null"));
        assertFalse(ToolFailureClassifier.fromHttp(422, "").isRetryable());
        assertEquals(ToolErrorCode.RATE_LIMITED, ToolFailureClassifier.fromHttp(429, "rate limit"));
        assertFalse(ToolFailureClassifier.fromHttp(429, "rate limit").isRetryable());
        assertTrue(ToolFailureClassifier.fromHttp(503, "").isRetryable());
    }

    @Test
    void routerSkipsAirportWhenFlightsNotNeeded() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.RUN_FLIGHTS, Boolean.FALSE);
        data.put(TravelState.RUN_HOTELS, Boolean.TRUE);
        data.put(TravelState.RUN_RESEARCH, Boolean.TRUE);
        data.put(TravelState.RUN_WEATHER, Boolean.TRUE);
        TravelState state = new TravelState(data);
        assertEquals(TravelGraphNodes.FAN_OUT, SpecialistRouter.afterPlanner(state));
        assertFalse(SpecialistRouter.plannedSpecialists(state).contains("flight"));
    }

    @Test
    void supervisorRetriesWhenRequiredFlightsUnavailable() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.NEEDS_FLIGHTS, Boolean.TRUE);
        data.put(TravelState.FLIGHTS, List.of());
        data.put(TravelState.RETRY_COUNT, 0);
        data.put(TravelState.MAX_RETRIES, 2);
        TravelState state = new TravelState(data);
        assertEquals(TravelGraphNodes.ROUTE_RETRY, new SupervisorAgentService(null, null).decide(state));
    }

    @Test
    void supervisorSkipBudgetWhenNotNeeded() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.RUN_BUDGET, Boolean.FALSE);
        data.put(TravelState.RUN_ITINERARY, Boolean.TRUE);
        TravelState state = new TravelState(data);
        assertEquals(TravelGraphNodes.ITINERARY, SpecialistRouter.afterSupervisor(state));
    }

    @Test
    void replanAddDestinationDoesNotDuplicate() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.DESTINATION, "Paris and Lyon");
        data.put(TravelState.COST_FACTOR, java.math.BigDecimal.ONE);
        data.put(TravelState.TRAVEL_STYLE, "balanced");
        data.put(TravelState.HOTEL_CHEAPER, Boolean.FALSE);
        data.put(TravelState.FLIGHT_PREFERENCE, "balanced");
        data.put(TravelState.RETRY_COUNT, 0);
        com.example.travel.model.ModificationRequest modification = new com.example.travel.model.ModificationRequest();
        modification.setDestination("Lyon");
        data.put(TravelState.MODIFICATION, modification);
        TravelState state = new TravelState(data);
        com.example.travel.model.ReplanStrategy strategy = new com.example.travel.model.ReplanStrategy();
        strategy.setActions(List.of("add_destination", "adjust_itinerary"));
        Map<String, Object> updates = new ReplanStrategyExecutor(new ReplanActionValidator()).apply(state, strategy);
        assertEquals(Boolean.TRUE, updates.get(TravelState.NEEDS_ITINERARY));
        assertEquals(Boolean.TRUE, updates.get(TravelState.NEEDS_RESEARCH));
        assertFalse(updates.containsKey(TravelState.DESTINATION));
    }

    @Test
    void modificationHeuristicParsesHotelBudgetAndFlightPreference() {
        var request = ModificationAgentService.heuristic("Set hotel budget to 50000 and prefer direct flights");
        assertEquals(new java.math.BigDecimal("50000"), request.getHotelBudget());
        assertEquals("direct", request.getFlightPreference());
    }

    @Test
    void supervisorRetriesOnRetryableNodeFailure() {
        NodeFailureInfo failure = new NodeFailureInfo();
        failure.setLastFailedNode(TravelGraphNodes.HOTEL);
        failure.setLastError("timeout");
        failure.setRetryable(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.NODE_FAILURE, failure);
        data.put(TravelState.RETRY_COUNT, 0);
        data.put(TravelState.MAX_RETRIES, 2);
        TravelState state = new TravelState(data);
        assertEquals(TravelGraphNodes.ROUTE_RETRY, new SupervisorAgentService(null, null).decide(state));
    }

    @Test
    void nodeFailureReplanTargetsFailedSpecialist() {
        NodeFailureInfo failure = new NodeFailureInfo();
        failure.setLastFailedNode(TravelGraphNodes.FLIGHT);
        failure.setRetryable(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.NODE_FAILURE, failure);
        data.put(TravelState.NEEDS_BUDGET, Boolean.TRUE);
        TravelState state = new TravelState(data);
        var strategy = NodeFailureRouting.replanForFailure(state);
        assertFalse(strategy.getActions().isEmpty());
        Map<String, Object> updates = new ReplanStrategyExecutor(new ReplanActionValidator()).apply(state, strategy);
        assertEquals(Boolean.TRUE, updates.get(TravelState.RUN_FLIGHTS));
    }

    @Test
    void lowQualityScoreTriggersReplan() {
        PlanQualityScore score = new PlanQualityScore();
        score.setOverall(0.62);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.PLAN_QUALITY, score);
        data.put(TravelState.RETRY_COUNT, 0);
        data.put(TravelState.MAX_RETRIES, 2);
        TravelState state = new TravelState(data);
        assertTrue(state.shouldReplan());
    }
}
