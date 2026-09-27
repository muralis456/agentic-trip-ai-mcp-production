package com.example.travel.agent;

import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.NodeFailureRouting;
import com.example.travel.graph.NodeFailureSupport;
import com.example.travel.graph.TravelState;
import com.example.travel.model.AgentDecision;
import com.example.travel.model.ReplanAction;
import com.example.travel.model.ReplanStrategy;
import com.example.travel.service.ReplanActionValidator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies validated replan actions and determines which specialist
 * agents should execute.
 */
@Component
public class ReplanStrategyExecutor {

    private final ReplanActionValidator replanActionValidator;

    public ReplanStrategyExecutor(
            ReplanActionValidator replanActionValidator) {

        this.replanActionValidator = replanActionValidator;
    }

    public Map<String, Object> apply(
            TravelState state,
            ReplanStrategy strategy) {

        List<ReplanAction> actions = replanActionValidator.resolve(
                strategy,
                state);

        strategy.setResolvedActions(actions);

        Map<String, Object> updates = new LinkedHashMap<>();

        BigDecimal factor = state.costFactor();

        boolean hotelCheaper = state.hotelCheaper();
        BigDecimal hotelBudget = state.hotelBudget();

        String flightPreference = state.flightPreference();

        String travelStyle = state.travelStyle();

        if (travelStyle == null) {
            travelStyle = "";
        }

        /*
         * Apply semantic actions selected by the LLM.
         */
        for (ReplanAction action : actions) {

            switch (action) {

                case GET_FLIGHT_DETAILS -> {
                    /*
                     * User wants flight information.
                     *
                     * Do not change flight preference.
                     *
                     * Specialist execution is handled by
                     * applySelectiveRouting().
                     */
                }

                         case GET_HOTEL_DETAILS, GET_BUDGET_BREAKDOWN,
                                    GET_WEATHER_DETAILS -> {
                                /*
                                 * Informational requests do not change the plan.
                                 * Specialist execution is handled by
                                 * applySelectiveRouting().
                                 */
                         }

                case CHEAPER_FLIGHT -> {

                    flightPreference = "cheapest";

                    factor = factor.multiply(
                            BigDecimal.valueOf(0.95));
                }

                case REDUCE_HOTEL_BUDGET -> {
                    // A hotel change invalidates derived cost and itinerary outputs.
                    // The routing phase below therefore schedules hotel first, then
                    // budget and itinerary in dependency order.
                    hotelCheaper = true;
                    if (state.modification() != null && state.modification().getHotelBudget() != null) {
                        hotelBudget = state.modification().getHotelBudget();
                    }
                }

                case HOTEL_UPGRADE -> {

                    hotelCheaper = false;

                    if (!travelStyle
                            .toLowerCase()
                            .contains("luxury")) {

                        travelStyle = "upscale";
                    }
                }

                case REMOVE_EXPENSIVE_ATTRACTIONS -> {

                    if (!state.attractions().isEmpty()) {

                        updates.put(
                                TravelState.ATTRACTIONS,
                                new ArrayList<>(
                                        state.attractions()
                                                .subList(
                                                        0,
                                                        Math.min(
                                                                3,
                                                                state.attractions()
                                                                        .size()))));
                    }
                }

                case ADD_DESTINATION -> {

                    if (state.modification() != null
                            && !TravelState.isBlank(
                                    state.modification()
                                            .getDestination())) {

                        String extra = state.modification()
                                .getDestination();

                        String current = TravelState.firstNonBlank(
                                state.destination());

            if (!TravelState.isBlank(current)
                                && !current
                                        .toLowerCase()
                                        .contains(
                                                extra.toLowerCase())) {

                            updates.put(
                                    TravelState.DESTINATION,
                                    current
                                            + " and "
                                            + extra);
                        }

                        updates.put(
                                TravelState.NEEDS_RESEARCH,
                                Boolean.TRUE);

                        updates.put(
                                TravelState.NEEDS_ITINERARY,
                                Boolean.TRUE);

                        updates.put(
                                TravelState.RUN_RESEARCH,
                                Boolean.TRUE);

                        updates.put(
                                TravelState.RUN_ITINERARY,
                                Boolean.TRUE);
                    }
                }

                case ADJUST_ITINERARY -> {

                    updates.put(
                            TravelState.NEEDS_ITINERARY,
                            Boolean.TRUE);

                    updates.put(
                            TravelState.RUN_ITINERARY,
                            Boolean.TRUE);
                }
            }
        }

        /*
         * Convert approved semantic actions into specialist
         * execution flags.
         */
        applySelectiveRouting(
                updates,
                actions,
                state);

        /*
         * Preserve node-failure recovery behavior.
         */
        NodeFailureRouting.applySelectiveNeeds(
                updates,
                state);

        // A deliberate hotel replan is a new attempt. Clear the terminal
        // fallback marker so the hotel specialist may try the provider again.
        if (Boolean.TRUE.equals(updates.get(TravelState.RUN_HOTELS))
                && actions.stream().anyMatch(action ->
                    action == ReplanAction.REDUCE_HOTEL_BUDGET
                    || action == ReplanAction.HOTEL_UPGRADE)) {
            updates.put(TravelState.HOTEL_FALLBACK_EXHAUSTED, Boolean.FALSE);
        }

        // The canonical AgentPlan remains the source of truth even during a
        // selective recovery pass. Project the newly selected specialist set
        // onto it so the dynamic router does not accidentally fan out the
        // entire original trip again.
        TravelState.projectRunSelection(updates, state);

        boolean semanticRepair = !state.semanticNotes().isEmpty();
        String notes = "Replan priority="
                + strategy.getPriority()
                + " actions="
                + actions
                + " cause="
                + (TravelState.isBlank(
                        strategy.getReason())
                                ? String.join(
                                        "; ",
                                        state.validationErrors())
                                        + " "
                                        + String.join(
                                                "; ",
                                                state.semanticNotes())
                                : strategy.getReason())
                + (semanticRepair ? " semanticRepairAttempt=true" : "");

        AgentDecision decision = new AgentDecision(
                "replanner",
                TravelState.firstNonBlank(
                        strategy.getPriority(),
                        "REPLAN").toUpperCase(),
                notes,
                strategy.getExpectedImpact() > 0
                        ? strategy.getExpectedImpact()
                        : 0.8);

        updates.put(
                TravelState.COST_FACTOR,
                factor);

        updates.put(
                TravelState.HOTEL_CHEAPER,
                hotelCheaper);

        updates.put(
                TravelState.HOTEL_BUDGET,
                hotelBudget == null ? TravelState.UNSET_BUDGET : hotelBudget);

        updates.put(
                TravelState.FLIGHT_PREFERENCE,
                flightPreference);

        updates.put(
                TravelState.TRAVEL_STYLE,
                travelStyle);

        updates.put(
                TravelState.REPLAN_NOTES,
                notes);

        updates.put(
                TravelState.REPLAN_STRATEGY,
                strategy);

        // A user modification starts a fresh validation pass. Do not let stale
        // errors/semantic notes from the previous version of the plan trigger
        // unrelated recovery specialists. New validation issues will be added
        // by ValidatorNode after the targeted specialist completes.
        if ("modify".equalsIgnoreCase(state.hitlDecision())) {
            updates.put(TravelState.VALIDATION_ERRORS, new ArrayList<String>());
            updates.put(TravelState.SEMANTIC_NOTES, new ArrayList<String>());
        }

        updates.put(
                TravelState.LAST_DECISION,
                decision);

        updates.putAll(
                NodeFailureSupport.clear());

        /*
         * Build logging information using the NEW selective
         * requirements, not the old checkpoint values.
         */
        Map<String, Object> selectiveNeeds = new LinkedHashMap<>();

        selectiveNeeds.put(
                "runFlights",
                updates.getOrDefault(TravelState.RUN_FLIGHTS, state.runFlights()));

        selectiveNeeds.put(
                "runHotels",
                updates.getOrDefault(TravelState.RUN_HOTELS, state.runHotels()));

        selectiveNeeds.put(
                "runResearch",
                updates.getOrDefault(TravelState.RUN_RESEARCH, state.runResearch()));

        selectiveNeeds.put(
                "runWeather",
                updates.getOrDefault(TravelState.RUN_WEATHER, state.runWeather()));

        selectiveNeeds.put(
                "runBudget",
                updates.getOrDefault(TravelState.RUN_BUDGET, state.runBudget()));

        selectiveNeeds.put(
                "runItinerary",
                updates.getOrDefault(TravelState.RUN_ITINERARY, state.runItinerary()));

        selectiveNeeds.put(
                "needsFlights",
                updates.getOrDefault(TravelState.NEEDS_FLIGHTS, state.needsFlights()));

        selectiveNeeds.put(
                "needsHotels",
                updates.getOrDefault(TravelState.NEEDS_HOTELS, state.needsHotels()));

        GraphExecutionLogger.replan(
                state,
                actions,
                selectiveNeeds);

        return updates;
    }

    private void applySelectiveRouting(
            Map<String, Object> updates,
            List<ReplanAction> actions,
            TravelState state) {

        // NEEDS_* describes the active workflow requirements after this replan.
        // Historical result data is retained separately in the checkpoint.
        boolean needsFlights = state.needsFlights();
        boolean needsHotels = state.needsHotels();
        boolean needsResearch = state.needsResearch();
        boolean needsWeather = state.needsWeather();
        boolean needsBudget = state.needsBudget();
        boolean needsItinerary = state.needsItinerary();

        // RUN_* is only for the current graph pass.
        boolean runFlights = false;
        boolean runHotels = false;
        boolean runResearch = false;
        boolean runWeather = false;
        boolean runBudget = false;
        boolean runItinerary = false;

        if (actions != null) {
            for (ReplanAction action : actions) {
                if (action == null) {
                    continue;
                }

                switch (action) {
                    case GET_FLIGHT_DETAILS -> {
                        runFlights = true;
                        needsFlights = true;
                    }
                    case CHEAPER_FLIGHT -> {
                        runFlights = true;
                        runBudget = true;
                        needsFlights = true;
                        needsBudget = true;
                    }
                    case GET_HOTEL_DETAILS, HOTEL_UPGRADE -> {
                        runHotels = true;
                        needsHotels = true;
                    }
                    case GET_WEATHER_DETAILS -> {
                        runWeather = true;
                        needsWeather = true;
                    }
                    case GET_BUDGET_BREAKDOWN -> {
                        runBudget = true;
                        needsBudget = true;
                    }
                    case REDUCE_HOTEL_BUDGET -> {
                        // Hotel is the changed source; budget and itinerary are
                        // downstream derived artifacts and must be recomputed after
                        // the new hotel result is persisted.
                        runHotels = true;
                        runBudget = true;
                        runItinerary = true;
                        needsHotels = true;
                        needsBudget = true;
                        needsItinerary = true;
                    }
                    case REMOVE_EXPENSIVE_ATTRACTIONS -> {
                        runResearch = true;
                        needsResearch = true;
                    }
                    case ADD_DESTINATION -> {
                        runResearch = true;
                        runItinerary = true;
                        needsResearch = true;
                        needsItinerary = true;
                    }
                    case ADJUST_ITINERARY -> {
                        runItinerary = true;
                        needsItinerary = true;
                    }
                }
            }
        }

        updates.put(TravelState.RUN_FLIGHTS, runFlights);
        updates.put(TravelState.RUN_HOTELS, runHotels);
        updates.put(TravelState.RUN_RESEARCH, runResearch);
        updates.put(TravelState.RUN_WEATHER, runWeather);
        updates.put(TravelState.RUN_BUDGET, runBudget);
        updates.put(TravelState.RUN_ITINERARY, runItinerary);

        updates.put(TravelState.NEEDS_FLIGHTS, needsFlights);
        updates.put(TravelState.NEEDS_HOTELS, needsHotels);
        updates.put(TravelState.NEEDS_RESEARCH, needsResearch);
        updates.put(TravelState.NEEDS_WEATHER, needsWeather);
        updates.put(TravelState.NEEDS_BUDGET, needsBudget);
        updates.put(TravelState.NEEDS_ITINERARY, needsItinerary);
    }

}