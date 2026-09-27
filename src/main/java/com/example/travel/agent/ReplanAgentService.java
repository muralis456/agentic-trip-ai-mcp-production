package com.example.travel.agent;

import com.example.travel.graph.NodeFailureRouting;
import com.example.travel.graph.TravelState;
import com.example.travel.model.ModificationRequest;
import com.example.travel.model.ReplanAction;
import com.example.travel.model.ReplanStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReplanAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReplanAgentService.class);

    private final IntentAgentService intentAgentService;
    private final ReplanStrategyExecutor replanStrategyExecutor;

    public ReplanAgentService(
            IntentAgentService intentAgentService,
            ReplanStrategyExecutor replanStrategyExecutor) {
        this.intentAgentService = intentAgentService;
        this.replanStrategyExecutor = replanStrategyExecutor;
    }

    /**
     * Handles three different situations:
     *
     * 1. A real HITL modification: classify the latest user request once.
     * 2. A specialist/node failure: recover only the failed specialist.
     * 3. A Supervisor retry without a new user request: preserve RUN_* flags.
     *
     * NEEDS_* is cumulative. RUN_* is current-pass routing.
     */
    public Map<String, Object> decide(TravelState state) {
        if (state == null) {
            return Map.of();
        }

        // HITL -> MODIFY enters Replan exactly once.
        if (isUserModification(state)) {
            ModificationRequest modification = state.modification();

            // Explicit structured changes (upgrade/reduce/add destination/itinerary)
            // use the already interpreted ModificationRequest.
            // A general information request is classified semantically by the Intent Agent.
            if (hasStructuredModificationAction(modification)) {
                ReplanStrategy modificationStrategy = fromModification(modification);
                Map<String, Object> updates = replanStrategyExecutor.apply(
                        state, modificationStrategy);
                if (Boolean.TRUE.equals(updates.get(TravelState.RUN_HOTELS))) {
                    updates.put(TravelState.HOTEL_FALLBACK_EXHAUSTED, Boolean.FALSE);
                }
                // A user modification is not a failed retry.
                updates.put(TravelState.RETRY_COUNT, state.retryCount());
                updates.put(TravelState.REQUEST_TYPE, com.example.travel.model.IntentPlan.TRIP_PLANNING);
                updates.put(TravelState.HITL_DECISION, "");
                updates.put(TravelState.AWAITING_APPROVAL, Boolean.TRUE);
                return updates;
            }

            Map<String, Object> intentUpdates = classifyLatestUserRequest(state);

            if (hasRunAction(intentUpdates)) {
                // A modification resumes the existing trip workflow, but RUN_*
                // remains narrowly scoped to the capabilities requested by this
                // modification. The dashboard may therefore preserve old results
                // without scheduling their specialists again.
                intentUpdates.put(TravelState.REQUEST_TYPE, com.example.travel.model.IntentPlan.TRIP_PLANNING);
                if (Boolean.TRUE.equals(intentUpdates.get(TravelState.RUN_HOTELS))) {
                    intentUpdates.put(TravelState.HOTEL_FALLBACK_EXHAUSTED, Boolean.FALSE);
                }
                // Consume the HITL modification marker. The next Supervisor retry
                // must NOT interpret the same user request again.
                intentUpdates.put(TravelState.HITL_DECISION, "");
                intentUpdates.put(TravelState.AWAITING_APPROVAL, Boolean.TRUE);

                log.info(
                        "Replanner consumed user modification; current RUN flags flights={} hotels={} research={} weather={} budget={} itinerary={}",
                        intentUpdates.get(TravelState.RUN_FLIGHTS),
                        intentUpdates.get(TravelState.RUN_HOTELS),
                        intentUpdates.get(TravelState.RUN_RESEARCH),
                        intentUpdates.get(TravelState.RUN_WEATHER),
                        intentUpdates.get(TravelState.RUN_BUDGET),
                        intentUpdates.get(TravelState.RUN_ITINERARY));

                return intentUpdates;
            }

            // Nothing actionable was requested. Consume the modification and
            // proceed without changing existing requirements/results.
            Map<String, Object> updates = preserveCurrentRun(state);
            updates.put(TravelState.HITL_DECISION, "");
            updates.put(TravelState.AWAITING_APPROVAL, Boolean.TRUE);
            return updates;
        }

        // Actual specialist/node failure is recovery, not a new user request.
        if (hasNodeFailure(state)) {
            ReplanStrategy strategy = NodeFailureRouting.replanForFailure(state);
            if (hasActions(strategy)) {
                log.info(
                        "Replanner node-failure recovery actions={} reason={}",
                        strategy.getActions(), strategy.getReason());
                return replanStrategyExecutor.apply(state, strategy);
            }
        }

        // Budget/validation recovery is a genuine replan.
        // This path is intentionally separate from user-intent processing.
        if (state.overBudget() || !state.validationErrors().isEmpty() || !state.semanticNotes().isEmpty()) {
            ReplanStrategy strategy = fallback(state);
            if (hasActions(strategy)) {
                return replanStrategyExecutor.apply(state, strategy);
            }
        }

        // Supervisor retry: never reinterpret the previous user request.
        // Rerun only specialists that failed in the current pass.
        return rerunFailedCurrentSpecialists(state);
    }

    private Map<String, Object> classifyLatestUserRequest(TravelState state) {
        Map<String, Object> updates = new LinkedHashMap<>(
                intentAgentService.classifyLatestRequest(state));

        log.info(
                "Replanner latest intent routing flights={} hotels={} research={} weather={} budget={} itinerary={}",
                updates.get(TravelState.RUN_FLIGHTS),
                updates.get(TravelState.RUN_HOTELS),
                updates.get(TravelState.RUN_RESEARCH),
                updates.get(TravelState.RUN_WEATHER),
                updates.get(TravelState.RUN_BUDGET),
                updates.get(TravelState.RUN_ITINERARY));

        updates.put(TravelState.REPLAN_NOTES, state.userRequest());
        return updates;
    }

    private Map<String, Object> preserveCurrentRun(TravelState state) {
        Map<String, Object> updates = new LinkedHashMap<>();

        updates.put(TravelState.NEEDS_FLIGHTS, state.needsFlights());
        updates.put(TravelState.NEEDS_HOTELS, state.needsHotels());
        updates.put(TravelState.NEEDS_RESEARCH, state.needsResearch());
        updates.put(TravelState.NEEDS_WEATHER, state.needsWeather());
        updates.put(TravelState.NEEDS_BUDGET, state.needsBudget());
        updates.put(TravelState.NEEDS_ITINERARY, state.needsItinerary());

        updates.put(TravelState.RUN_FLIGHTS, state.runFlights());
        updates.put(TravelState.RUN_HOTELS, state.runHotels());
        updates.put(TravelState.RUN_RESEARCH, state.runResearch());
        updates.put(TravelState.RUN_WEATHER, state.runWeather());
        updates.put(TravelState.RUN_BUDGET, state.runBudget());
        updates.put(TravelState.RUN_ITINERARY, state.runItinerary());

        return updates;
    }

        private Map<String, Object> rerunFailedCurrentSpecialists(TravelState state) {
        Map<String, Object> updates = new LinkedHashMap<>();

        updates.put(TravelState.NEEDS_FLIGHTS, state.needsFlights());
        updates.put(TravelState.NEEDS_HOTELS, state.needsHotels());
        updates.put(TravelState.NEEDS_RESEARCH, state.needsResearch());
        updates.put(TravelState.NEEDS_WEATHER, state.needsWeather());
        updates.put(TravelState.NEEDS_BUDGET, state.needsBudget());
        updates.put(TravelState.NEEDS_ITINERARY, state.needsItinerary());

        updates.put(TravelState.RUN_FLIGHTS, state.runFlights()
            && !state.hasUsableFlights()
            && !hasPermanentFlightFailure(state));
        updates.put(TravelState.RUN_HOTELS, state.runHotels()
            && state.hotels().isEmpty()
            && !state.hotelFallbackExhausted());
        updates.put(TravelState.RUN_RESEARCH, state.runResearch()
            && state.research().isEmpty() && state.attractions().isEmpty());
        updates.put(TravelState.RUN_WEATHER, state.runWeather()
            && (state.weather() == null
                || (TravelState.isBlank(state.weather().getSummary())
                    && (state.weather().getDays() == null || state.weather().getDays().isEmpty()))));
        updates.put(TravelState.RUN_BUDGET, state.runBudget());
        updates.put(TravelState.RUN_ITINERARY, state.runItinerary());

        TravelState.projectRunSelection(updates, state);
        log.info("Replanner current-pass recovery flights={} hotels={} research={} weather={} budget={} itinerary={}",
            updates.get(TravelState.RUN_FLIGHTS), updates.get(TravelState.RUN_HOTELS),
            updates.get(TravelState.RUN_RESEARCH), updates.get(TravelState.RUN_WEATHER),
            updates.get(TravelState.RUN_BUDGET), updates.get(TravelState.RUN_ITINERARY));
        return updates;
        }

        private boolean hasPermanentFlightFailure(TravelState state) {
        return state.flights().stream()
            .filter(flight -> flight != null && "unavailable".equalsIgnoreCase(flight.getStatus()))
            .map(flight -> flight.getNotes() == null ? "" : flight.getNotes().toLowerCase())
            .anyMatch(notes -> notes.contains("not configured")
                || notes.contains("invalid")
                || notes.contains("not allowed")
                || notes.contains("does not expose"));
        }

    private boolean isUserModification(TravelState state) {
        return state != null
                && state.modification() != null
                && ("modify".equalsIgnoreCase(state.hitlDecision())
                || hasStructuredModificationAction(state.modification()));
    }

    private boolean hasRunAction(Map<String, Object> updates) {
        return Boolean.TRUE.equals(updates.get(TravelState.RUN_FLIGHTS))
                || Boolean.TRUE.equals(updates.get(TravelState.RUN_HOTELS))
                || Boolean.TRUE.equals(updates.get(TravelState.RUN_RESEARCH))
                || Boolean.TRUE.equals(updates.get(TravelState.RUN_WEATHER))
                || Boolean.TRUE.equals(updates.get(TravelState.RUN_BUDGET))
                || Boolean.TRUE.equals(updates.get(TravelState.RUN_ITINERARY));
    }

    private boolean hasStructuredModificationAction(ModificationRequest modification) {
        if (modification == null) {
            return false;
        }
        String type = modification.getChangeType();
        return ModificationRequest.ADD_DESTINATION.equalsIgnoreCase(type)
                || ModificationRequest.HOTEL_UPGRADE.equalsIgnoreCase(type)
                || ModificationRequest.REDUCE_COST.equalsIgnoreCase(type)
                || ModificationRequest.ITINERARY_CHANGE.equalsIgnoreCase(type);
    }

    private boolean hasNodeFailure(TravelState state) {
        return state.nodeFailure() != null
                && !TravelState.isBlank(state.nodeFailure().getLastFailedNode());
    }

    private boolean hasActions(ReplanStrategy strategy) {
        return strategy != null
                && strategy.getActions() != null
                && !strategy.getActions().isEmpty();
    }

    /** Structured fallback only; no keyword matching. */
    private ReplanStrategy fromModification(ModificationRequest modification) {
        ReplanStrategy strategy = new ReplanStrategy();
        if (modification == null) {
            return strategy;
        }

        if (modification.isAddDestination()) {
            strategy.setPriority("research");
            strategy.setReason("add_destination");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.ADD_DESTINATION.wireName())));
        } else if (modification.isHotelUpgrade()) {
            strategy.setPriority("hotel");
            strategy.setReason("hotel_upgrade");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.HOTEL_UPGRADE.wireName())));
        } else if (modification.isHotelCostReduction()) {
            strategy.setPriority("hotel");
            strategy.setReason("reduce_hotel_cost");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.REDUCE_HOTEL_BUDGET.wireName())));
        } else if (modification.isFlightCostReduction()) {
            strategy.setPriority("flight");
            strategy.setReason("reduce_flight_cost");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.CHEAPER_FLIGHT.wireName())));
        } else if (modification.isReduceCost()) {
            strategy.setPriority("cost");
            strategy.setReason("reduce_trip_cost");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.REDUCE_HOTEL_BUDGET.wireName(),
                    ReplanAction.CHEAPER_FLIGHT.wireName())));
        } else if (ModificationRequest.ITINERARY_CHANGE.equalsIgnoreCase(modification.getChangeType())) {
            strategy.setPriority("itinerary");
            strategy.setReason("itinerary_change");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.ADJUST_ITINERARY.wireName())));
        }

        return strategy;
    }

    private ReplanStrategy fallback(TravelState state) {
        ReplanStrategy strategy = new ReplanStrategy();

        if (state.overBudget()) {
            strategy.setReason("budget_exceeded");
            strategy.setPriority("hotel");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.REDUCE_HOTEL_BUDGET.wireName(),
                    ReplanAction.CHEAPER_FLIGHT.wireName())));
        } else if (!state.semanticNotes().isEmpty()) {
            strategy.setReason("semantic_mismatch");
            strategy.setPriority("itinerary");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.ADJUST_ITINERARY.wireName())));
        } else {
            strategy.setReason("validation_failed");
            strategy.setPriority("attractions");
            strategy.setActions(new ArrayList<>(List.of(
                    ReplanAction.REMOVE_EXPENSIVE_ATTRACTIONS.wireName())));
        }

        if (state.budgetSummary() != null
                && state.budgetSummary().getEstimatedCost() != null
                && state.budget() != null) {
            BigDecimal estimated = state.budgetSummary().getEstimatedCost();
            BigDecimal budget = state.budget();
            strategy.setTargetReduction(estimated.subtract(budget).doubleValue());
        }
        return strategy;
    }
}
