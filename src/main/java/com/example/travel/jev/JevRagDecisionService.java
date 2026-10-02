package com.example.travel.jev;

import com.example.travel.graph.TravelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@ConditionalOnBean(JevDecisionService.class)
public class JevRagDecisionService {
    private static final Logger log = LoggerFactory.getLogger(JevRagDecisionService.class);
    private final JevDecisionService decisions;

    public JevRagDecisionService(JevDecisionService decisions) {
        this.decisions = decisions;
    }

    public Decision decide(TravelState state) {
        log.info(
                "jev.rag.decision-start needsKnowledge={} hasRagContext={}",
                state.needsKnowledge(),
                !TravelState.isBlank(state.ragContext()));

        String fallback = state.needsKnowledge() ? "RAG" : "NONE";
        try {
            var d = decisions.choose(
                    JevDecisionPolicy.DecisionKind.RAG,
                    Map.of(
                            "request", state.userRequest(),
                            "destination", state.destination(),
                            "needsKnowledge", state.needsKnowledge(),
                            "existingRagContext", !TravelState.isBlank(state.ragContext())),
                    "Choose the evidence route for this request. Use RAG for durable knowledge, WEB for current/live information, BOTH when both are needed, and NONE when no external knowledge retrieval is needed.",
                    Map.of(
                            "RAG", "Durable knowledge in the internal knowledge base is relevant.",
                            "WEB", "Fresh/current web information is required.",
                            "BOTH", "Durable internal knowledge and fresh web information are both required.",
                            "NONE", "No external knowledge retrieval is needed."));

            String c = d.choice().toUpperCase();
            boolean valid = Map.of("RAG", 1, "WEB", 1, "BOTH", 1, "NONE", 1).containsKey(c);
            Decision result = d.accepted() && valid
                    ? new Decision(c, d.confidence(), true, "jev-policy-accepted")
                    : new Decision(
                            fallback,
                            d.confidence(),
                            false,
                            d.accepted() ? "invalid-jev-choice" : "deterministic-rag-fallback");

            log.info(
                    "jev.rag.decision outcome={} recommendation={} confidence={} accepted={} reason={} model={}",
                    result.route(),
                    c,
                    result.confidence(),
                    result.accepted(),
                    result.reason(),
                    d.model());
            return result;
        } catch (Exception ex) {
            log.warn(
                    "jev.rag.decision-fallback outcome={} reason={}",
                    fallback,
                    ex.getClass().getSimpleName());
            return new Decision(fallback, 0, false, "jev unavailable: " + ex.getClass().getSimpleName());
        }
    }

    public record Decision(String route, double confidence, boolean accepted, String reason) { }
}
