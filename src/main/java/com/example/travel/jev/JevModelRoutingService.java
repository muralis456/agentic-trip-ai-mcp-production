package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import com.example.travel.service.ModelRoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevModelRoutingService {
    private static final Logger log = LoggerFactory.getLogger(JevModelRoutingService.class);
    private final JevDecisionService decisions;

    public JevModelRoutingService(JevDecisionService decisions) {
        this.decisions = decisions;
    }

    public String choose(TravelState state) {
        String fallback = ModelRoutingContext.normalize(state.modelPolicy());
        String selectedModel = state.selectedModel();

        if (selectedModel != null && selectedModel.contains(":")) {
            String explicit = selectedModel.trim();
            log.info("jev.model-routing bypass explicitModel={} reason=explicit-model-selection", explicit);
            return explicit;
        }

        log.info(
                "jev.model-routing.decision-start currentPolicy={} complexity={}",
                fallback,
                ModelRoutingContext.getComplexity());

        try {
            var d = decisions.choose(
                    JevDecisionPolicy.DecisionKind.MODEL_ROUTING,
                    Map.of(
                            "request", state.userRequest(),
                            "currentPolicy", fallback,
                            "complexity", ModelRoutingContext.getComplexity().name(),
                            "tripPlanning", state.isTripPlanningWorkflow(),
                            "requestType", state.requestType(),
                            "needsFlights", state.needsFlights(),
                            "needsHotels", state.needsHotels(),
                            "needsResearch", state.needsResearch(),
                            "needsItinerary", state.needsItinerary(),
                            "needsKnowledge", state.needsKnowledge()),
                    "Choose the model policy for this graph turn. Prefer FAST for simple extraction, BALANCED for normal planning, and REASONING for complex multi-constraint recovery.",
                    Map.of(
                            "FAST", "Simple, low-risk structured work.",
                            "BALANCED", "Normal travel planning and synthesis.",
                            "REASONING", "Complex constraints, recovery, or ambiguous multi-step decisions."));

            String c = d.choice().toUpperCase();
            String result = d.accepted()
                    && Map.of("FAST", 1, "BALANCED", 1, "REASONING", 1).containsKey(c)
                    ? c
                    : fallback;

            log.info(
                    "jev.model-routing.decision outcome={} recommendation={} confidence={} accepted={} reason={} model={}",
                    result,
                    c,
                    d.confidence(),
                    d.accepted(),
                    d.accepted() ? "jev-policy-accepted" : "deterministic-model-policy-fallback",
                    d.model());
            return result;
        } catch (Exception ex) {
            log.warn(
                    "jev.model-routing.decision-fallback outcome={} reason={}",
                    fallback,
                    ex.getClass().getSimpleName());
            return fallback;
        }
    }
}
