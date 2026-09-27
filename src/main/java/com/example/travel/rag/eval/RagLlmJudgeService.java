package com.example.travel.rag.eval;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.model.Itinerary;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.springframework.stereotype.Service;

/**
 * LLM-as-a-judge for RAG groundedness. It checks whether the generated plan
 * uses claims supported by retrieved durable knowledge. It never treats live
 * MCP data (flight, hotel, weather, currency) as RAG evidence.
 */
@Service
public class RagLlmJudgeService {
    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;

    public RagLlmJudgeService(RoutedLlm routedLlm, JsonSupport jsonSupport) {
        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
    }

    public JudgeResult judge(TravelState state) {
        if (!state.ragSufficient() || state.ragContext().isBlank()) {
            return new JudgeResult(1.0, 1.0, 1.0, true, "RAG evidence not required or unavailable.");
        }
        Itinerary itinerary = state.itinerary();
        String answer = !state.ragAnswer().isBlank() ? state.ragAnswer()
                : (itinerary == null ? state.finalTips() : itinerary.toDisplay());
        if (answer == null || answer.isBlank()) {
            return new JudgeResult(0.0, 0.0, 0.0, false, "No generated answer was available to judge.");
        }
        final String raw;
        try {
            raw = routedLlm.complete(AgentRole.EXTRACT,
                    """
                    You are an evidence-groundedness evaluator for a travel AI system.
                    Judge ONLY claims that depend on the supplied durable RAG context.
                    Ignore live facts such as flight availability, hotel prices, weather and currency.
                    Penalize unsupported destination rules, packing guidance, culture, safety or policy claims.
                    A good answer can contain additional live-tool facts; do not penalize those.
                    Return JSON only:
                    {"groundedness":0.0,"coverage":0.0,"unsupportedClaimRate":0.0,"pass":true,"reason":"short reason"}
                    Pass normally requires groundedness >= 0.80, coverage >= 0.70 and unsupportedClaimRate <= 0.20.
                    """,
                    "USER REQUEST:\n%s\n\nRAG CONTEXT:\n%s\n\nGENERATED ANSWER:\n%s"
                            .formatted(state.userRequest(), state.ragContext(), answer));
        } catch (Exception ex) {
            // A validator must never turn a temporary LLM-budget problem into
            // a destructive replan when grounded RAG evidence already exists.
            double evidence = state.ragEvidenceScore();
            boolean pass = evidence >= 0.50;
            return new JudgeResult(evidence, evidence, Math.max(0, 1.0 - evidence), pass,
                    "RAG judge unavailable; used deterministic evidence score.");
        }
        return jsonSupport.readTree(raw).map(n -> {
            double grounded = clamp(n.path("groundedness").asDouble(0));
            double coverage = clamp(n.path("coverage").asDouble(0));
            double unsupported = clamp(n.path("unsupportedClaimRate").asDouble(1));
            boolean pass = n.path("pass").asBoolean(grounded >= 0.80 && coverage >= 0.70 && unsupported <= 0.20);
            String reason = n.path("reason").asString("No reason supplied.");
            return new JudgeResult(grounded, coverage, unsupported, pass, reason);
        }).orElseGet(() -> new JudgeResult(0, 0, 1, false, "LLM judge returned invalid JSON."));
    }

    public JudgeResult judge(String answer, String context, String userRequest) {
        if (answer == null || answer.isBlank() || context == null || context.isBlank()) {
            return new JudgeResult(0, 0, 1, false, "Answer and context are required.");
        }
        String raw = routedLlm.complete(AgentRole.EXTRACT,
                """
                Evaluate answer groundedness against supplied evidence. Ignore live-tool claims.
                Return JSON only: {"groundedness":0.0,"coverage":0.0,"unsupportedClaimRate":0.0,"pass":true,"reason":"short reason"}
                """,
                "USER REQUEST:\n%s\n\nEVIDENCE:\n%s\n\nANSWER:\n%s".formatted(userRequest, context, answer));
        return jsonSupport.readTree(raw).map(n -> {
            double grounded = clamp(n.path("groundedness").asDouble(0));
            double coverage = clamp(n.path("coverage").asDouble(0));
            double unsupported = clamp(n.path("unsupportedClaimRate").asDouble(1));
            boolean pass = n.path("pass").asBoolean(grounded >= 0.80 && coverage >= 0.70 && unsupported <= 0.20);
            return new JudgeResult(grounded, coverage, unsupported, pass, n.path("reason").asString(""));
        }).orElseGet(() -> new JudgeResult(0, 0, 1, false, "LLM judge returned invalid JSON."));
    }

    private double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    public record JudgeResult(double groundedness, double coverage, double unsupportedClaimRate,
                              boolean pass, String reason) {}
}
