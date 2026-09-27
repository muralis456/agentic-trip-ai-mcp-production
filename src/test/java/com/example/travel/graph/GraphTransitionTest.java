package com.example.travel.graph;

import com.example.travel.agent.ReplanStrategyExecutor;
import com.example.travel.agent.ReplanAgentService;
import com.example.travel.agent.SupervisorAgentService;
import com.example.travel.graph.node.ReplanNode;
import com.example.travel.model.PlanQualityScore;
import com.example.travel.model.ReplanStrategy;
import com.example.travel.service.ReplanActionValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph routing and replan transitions without Spring or live LLM calls.
 */
class GraphTransitionTest {

    private final ReplanStrategyExecutor replanExecutor = new ReplanStrategyExecutor(new ReplanActionValidator());

    @Test
    void plannerRoutesToAirportWhenFlightsNeeded() {
        TravelState state = state(Map.of(
                TravelState.RUN_FLIGHTS, Boolean.TRUE,
                TravelState.RUN_HOTELS, Boolean.TRUE));
        assertEquals(TravelGraphNodes.AIRPORT, SpecialistRouter.afterPlanner(state));
    }

    @Test
    void soleSpecialistBypassesFanOut() {
        TravelState state = state(Map.of(
                TravelState.RUN_FLIGHTS, Boolean.FALSE,
                TravelState.RUN_HOTELS, Boolean.TRUE,
                TravelState.RUN_RESEARCH, Boolean.FALSE,
                TravelState.RUN_WEATHER, Boolean.FALSE));
        assertEquals(TravelGraphNodes.HOTEL, SpecialistRouter.specialistEntry(state));
        assertEquals(TravelGraphNodes.HOTEL, SpecialistRouter.afterPlanner(state));
    }

    @Test
    void multipleSpecialistsUseFanOut() {
        TravelState state = state(Map.of(
                TravelState.RUN_FLIGHTS, Boolean.FALSE,
                TravelState.RUN_HOTELS, Boolean.TRUE,
                TravelState.RUN_RESEARCH, Boolean.TRUE,
                TravelState.RUN_WEATHER, Boolean.FALSE));
        assertEquals(TravelGraphNodes.FAN_OUT, SpecialistRouter.afterPlanner(state));
        assertEquals(List.of(TravelGraphNodes.HOTEL, TravelGraphNodes.RESEARCH),
                SpecialistRouter.plannedSpecialists(state));
    }

    @Test
    void plannerSkipsSpecialistsWhenNothingRequested() {
        TravelState state = state(Map.of(
                TravelState.RUN_FLIGHTS, Boolean.FALSE,
                TravelState.RUN_HOTELS, Boolean.FALSE,
                TravelState.RUN_RESEARCH, Boolean.FALSE,
                TravelState.RUN_WEATHER, Boolean.FALSE,
                TravelState.RUN_BUDGET, Boolean.TRUE));
        assertEquals(TravelGraphNodes.SUPERVISOR, SpecialistRouter.afterPlanner(state));
    }

    @Test
    void supervisorRetryRoutesToReplan() {
        TravelState state = state(Map.of(
                TravelState.SUPERVISOR_DECISION, TravelGraphNodes.ROUTE_RETRY));
        assertEquals(TravelGraphNodes.REPLAN, SpecialistRouter.afterSupervisor(state));
    }

    @Test
    void supervisorRetryAtMaxRetriesContinuesToDownstreamWork() {
        TravelState state = state(Map.of(
                TravelState.SUPERVISOR_DECISION, TravelGraphNodes.ROUTE_RETRY,
                TravelState.RETRY_COUNT, 2,
                TravelState.MAX_RETRIES, 2,
                TravelState.RUN_BUDGET, Boolean.TRUE));

        assertEquals(TravelGraphNodes.BUDGET, SpecialistRouter.afterSupervisor(state));
    }

    @Test
    void replanNodeSkipsInternalRecoveryAtMaxRetries() {
        TravelState state = state(Map.of(
                TravelState.RETRY_COUNT, 2,
                TravelState.MAX_RETRIES, 2,
                TravelState.SUPERVISOR_DECISION, TravelGraphNodes.ROUTE_RETRY));

        Map<String, Object> updates = new ReplanNode(null).apply(state);

        assertEquals(2, updates.get(TravelState.RETRY_COUNT));
        assertEquals(TravelGraphNodes.ROUTE_PROCEED, updates.get(TravelState.SUPERVISOR_DECISION));
    }

    @Test
    void supervisorProceedSkipsBudgetWhenNotNeeded() {
        TravelState state = state(Map.of(
                TravelState.SUPERVISOR_DECISION, TravelGraphNodes.ROUTE_PROCEED,
                TravelState.RUN_BUDGET, Boolean.FALSE,
                TravelState.RUN_ITINERARY, Boolean.TRUE));
        assertEquals(TravelGraphNodes.ITINERARY, SpecialistRouter.afterSupervisor(state));
    }

    @Test
    void lowPlanQualityTriggersReplan() {
        PlanQualityScore score = new PlanQualityScore();
        score.setOverall(0.55);
        TravelState state = state(Map.of(
                TravelState.PLAN_QUALITY, score,
                TravelState.RETRY_COUNT, 0,
                TravelState.MAX_RETRIES, 2,
                TravelState.VALIDATION_ERRORS, List.of()));
        assertTrue(state.shouldReplan());
    }

    @Test
    void replanCheaperFlightSelectsOnlyFlightAndBudget() {
        TravelState state = state(Map.of(
                TravelState.COST_FACTOR, BigDecimal.ONE,
                TravelState.TRAVEL_STYLE, "balanced",
                TravelState.HOTEL_CHEAPER, Boolean.FALSE,
                TravelState.FLIGHT_PREFERENCE, "balanced",
                TravelState.RETRY_COUNT, 0,
                TravelState.NEEDS_FLIGHTS, Boolean.TRUE,
                TravelState.NEEDS_HOTELS, Boolean.TRUE,
                TravelState.NEEDS_BUDGET, Boolean.TRUE,
                TravelState.NEEDS_ITINERARY, Boolean.TRUE));
        ReplanStrategy strategy = new ReplanStrategy();
        strategy.setActions(List.of("cheaper_flight"));
        Map<String, Object> updates = replanExecutor.apply(state, strategy);
        assertEquals(Boolean.TRUE, updates.get(TravelState.RUN_FLIGHTS));
        assertEquals(Boolean.TRUE, updates.get(TravelState.RUN_BUDGET));
        assertEquals(Boolean.FALSE, updates.get(TravelState.RUN_HOTELS));
        assertEquals(Boolean.FALSE, updates.get(TravelState.RUN_ITINERARY));
        assertEquals(Boolean.TRUE, state.needsHotels());
    }

    @Test
    void hotelPricesHighSelectsHotelBudgetItineraryReplan() {
        var modification = com.example.travel.agent.ModificationAgentService.heuristic("Hotel prices are high");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TravelState.COST_FACTOR, BigDecimal.ONE);
        data.put(TravelState.TRAVEL_STYLE, "balanced");
        data.put(TravelState.HOTEL_CHEAPER, Boolean.FALSE);
        data.put(TravelState.FLIGHT_PREFERENCE, "balanced");
        data.put(TravelState.RETRY_COUNT, 0);
        data.put(TravelState.NEEDS_FLIGHTS, Boolean.TRUE);
        data.put(TravelState.NEEDS_HOTELS, Boolean.TRUE);
        data.put(TravelState.NEEDS_RESEARCH, Boolean.TRUE);
        data.put(TravelState.NEEDS_WEATHER, Boolean.TRUE);
        data.put(TravelState.NEEDS_BUDGET, Boolean.TRUE);
        data.put(TravelState.NEEDS_ITINERARY, Boolean.TRUE);
        data.put(TravelState.MODIFICATION, modification);
        TravelState state = state(data);

        Map<String, Object> updates = new ReplanAgentService(null, replanExecutor).decide(state);
        ReplanStrategy strategy = (ReplanStrategy) updates.get(TravelState.REPLAN_STRATEGY);

        assertEquals(List.of(com.example.travel.model.ReplanAction.REDUCE_HOTEL_BUDGET),
                strategy.getResolvedActions());
        assertEquals(Boolean.TRUE, updates.get(TravelState.RUN_HOTELS));
        assertEquals(Boolean.TRUE, updates.get(TravelState.RUN_BUDGET));
        assertEquals(Boolean.TRUE, updates.get(TravelState.RUN_ITINERARY));
        assertEquals(Boolean.FALSE, updates.get(TravelState.RUN_FLIGHTS));
        assertEquals(Boolean.FALSE, updates.get(TravelState.RUN_RESEARCH));
        assertEquals(Boolean.FALSE, updates.get(TravelState.RUN_WEATHER));
    }

    @Test
    void supervisorDoesNotRetryHotelAfterFallbackIsExhausted() {
        TravelState state = state(Map.of(
                TravelState.RUN_HOTELS, Boolean.TRUE,
                TravelState.HOTELS, List.of(),
                TravelState.HOTEL_FALLBACK_EXHAUSTED, Boolean.TRUE,
                TravelState.RETRY_COUNT, 0,
                TravelState.MAX_RETRIES, 2));

        assertEquals(TravelGraphNodes.ROUTE_PROCEED,
                new SupervisorAgentService(null, null).decide(state));
    }

    @Test
    void supervisorRetriesWhenRequiredFlightsMissing() {
        TravelState state = state(Map.of(
                TravelState.NEEDS_FLIGHTS, Boolean.TRUE,
                TravelState.FLIGHTS, List.of(),
                TravelState.RETRY_COUNT, 0,
                TravelState.MAX_RETRIES, 2));
        assertEquals(TravelGraphNodes.ROUTE_RETRY, new SupervisorAgentService(null, null).decide(state));
    }

    @Test
    void supervisorProceedsAtMaxRetriesEvenWhenFlightsMissing() {
        TravelState state = state(Map.of(
                TravelState.NEEDS_FLIGHTS, Boolean.TRUE,
                TravelState.FLIGHTS, List.of(),
                TravelState.RETRY_COUNT, 2,
                TravelState.MAX_RETRIES, 2));
        assertEquals(TravelGraphNodes.ROUTE_PROCEED, new SupervisorAgentService(null, null).decide(state));
    }

    @Test
    void includeReportSectionsWhenResultsExistDespiteSelectiveReplanFlags() {
        TravelState state = state(Map.of(
                TravelState.NEEDS_FLIGHTS, Boolean.FALSE,
                TravelState.NEEDS_HOTELS, Boolean.FALSE,
                TravelState.NEEDS_BUDGET, Boolean.FALSE,
                TravelState.FLIGHTS, List.of(flight("BLR", "NRT")),
                TravelState.HOTELS, List.of(),
                TravelState.BUDGET_SUMMARY, budgetSummary(120_000)));
        assertTrue(state.includeFlightsInReport());
        assertFalse(state.includeHotelsInReport());
        assertTrue(state.includeBudgetInReport());
    }

    private static com.example.travel.model.FlightOption flight(String from, String to) {
        com.example.travel.model.FlightOption option = new com.example.travel.model.FlightOption();
        option.setOrigin(from);
        option.setDestination(to);
        option.setStatus("available");
        return option;
    }

    private static com.example.travel.model.BudgetSummary budgetSummary(int total) {
        com.example.travel.model.BudgetSummary summary = new com.example.travel.model.BudgetSummary();
        summary.setEstimatedCost(java.math.BigDecimal.valueOf(total));
        summary.setWithinBudget(true);
        return summary;
    }

    private static TravelState state(Map<String, Object> values) {
        Map<String, Object> data = new LinkedHashMap<>(values);
        return new TravelState(data);
    }
}
