package com.example.travel.graph.node;

import com.example.travel.agent.FinalPlannerAgentService;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finalization node: generates travel tips only. The domain plan is assembled by {@link com.example.travel.service.TripPlanAssembler}.
 */
@Component
public class FinalizationNode implements NodeAction<TravelState> {

    private static final Logger log = LoggerFactory.getLogger(FinalizationNode.class);

    private final FinalPlannerAgentService finalPlannerAgentService;

    public FinalizationNode(FinalPlannerAgentService finalPlannerAgentService) {
        this.finalPlannerAgentService = finalPlannerAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        boolean tripPlanning = state != null && state.isTripPlanningWorkflow();
        boolean hasCompletedItinerary = state != null
                && state.itinerary() != null
                && state.itinerary().getDays() != null
                && !state.itinerary().getDays().isEmpty();

        // Every generated trip plan must stop at the human decision boundary.
        // Do NOT make approval depend on goalEvaluation=ACHIEVED: a trip can be
        // usable but PARTIAL when a provider (for example flights) is unavailable.
        // In that case the UI must still ask the human to approve/modify/reject
        // the generated plan instead of silently treating it as confirmed.
        boolean clarificationRequired = state != null && state.userInputRequired();
        boolean requiresApproval = clarificationRequired || (tripPlanning && !clarificationRequired);

        log.info(
                "[HITL] finalization tripPlanning={} goalStatus={} itineraryDays={} clarificationRequired={} requiresApproval={} ",
                tripPlanning,
                state != null && state.goalEvaluation() != null ? state.goalEvaluation().getStatus() : null,
                hasCompletedItinerary && state.itinerary() != null && state.itinerary().getDays() != null
                        ? state.itinerary().getDays().size() : 0,
                clarificationRequired,
                requiresApproval);

        String tips = "";
        if ("HISTORY".equalsIgnoreCase(state.requestType())) {
            // HistoryNode may already have produced a safe no-result message.
            // Never overwrite it with an empty finalization payload.
            tips = state.finalTips();
        } else if (clarificationRequired) {
            tips = state.userInputQuestion();
        } else if (requiresApproval) {
            // Keep durable RAG knowledge separate from generated trip tips.
            // The API exposes RAG guidance as plan.knowledge so the UI can
            // label it clearly and show its provenance without mixing it into
            // the generic Travel Notes section.
            tips = finalPlannerAgentService.buildTips(state);
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.AWAITING_APPROVAL, requiresApproval);
        updates.put(TravelState.FINAL_TIPS, tips == null ? "" : tips);
        updates.putAll(TravelState.trace(TravelGraphNodes.FINAL, "ok",
                state.validationErrors().isEmpty()
                        ? (requiresApproval ? "trip plan ready for approval" : "informational response complete")
                        : (requiresApproval ? "trip plan ready with caveats" : "informational response complete with caveats")));
        return updates;
    }
}
