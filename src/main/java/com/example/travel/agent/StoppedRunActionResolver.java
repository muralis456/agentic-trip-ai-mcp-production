package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.model.AgentTask;
import com.example.travel.model.StoppedRunActionDecision;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves a user's natural-language input against a stopped checkpoint.
 * This is deliberately separate from normal intent classification: a stopped
 * run is an existing execution context, not a new request to classify from zero.
 */
@Service
public class StoppedRunActionResolver {
    private static final Logger log = LoggerFactory.getLogger(StoppedRunActionResolver.class);

    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;

    public StoppedRunActionResolver(RoutedLlm routedLlm, JsonSupport jsonSupport) {
        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
    }

    public StoppedRunActionDecision resolve(TravelState checkpoint, String userInput) {
        String current = userInput == null ? "" : userInput.trim();
        String original = checkpoint == null ? "" : checkpoint.userRequest();
        if (current.isBlank()) return decision("RESUME", 1.0,
                "No new requirement was supplied; resume the saved work",
                original);

        if (isClearlyUnrelatedNewRequest(original, current)) {
            String effective = current;
            log.info("Stopped-run explicit new-request classification prompt={} original={} reason=unrelated-trip",
                current, original);
            return decision("NEW_REQUEST", 0.99,
                "The user is asking for a different trip entirely; do not continue the saved checkpoint.",
                effective);
        }

        String heuristicAction = heuristicAction(current);
        if (heuristicAction != null) {
            String effective = heuristicEffectiveRequest(original, current, heuristicAction);
            log.info("Stopped-run heuristic action={} confidence={} prompt={}", heuristicAction, 0.95, current);
            return decision(heuristicAction, 0.95,
                    "Detected a material requirement change in the current turn; reframe the full request against the saved checkpoint.",
                    effective);
        }

        String state = checkpointSummary(checkpoint);

        String system = """
                You are the TURN REFRAMER for an agentic travel coding/workflow system.
                A previous user request is already being executed and has a durable checkpoint.
                The user has now sent a NEW conversational turn. Your job is to understand that
                turn together with the previous requirement and produce the effective requirement
                for the NEXT execution pass. Think like an interactive coding agent: preserve what
                has already been done, incorporate the new requirement, and act on the resulting
                intent rather than starting from zero.

                ACTIONS:
                RESUME       - no material requirement change; continue unfinished work.
                MODIFY       - same task/request, but the user adds, removes, corrects or changes
                               a requirement. Reframe the COMPLETE requirement, not only the delta.
                NEW_REQUEST  - clearly unrelated new work.
                HISTORY      - explicitly asks to retrieve/show saved history instead of acting.

                CRITICAL RULES:
                - Never classify a short message as HISTORY merely because it is short.
                - "continue", "go ahead", "proceed", "keep going", "do it" normally mean RESUME
                  when a stopped checkpoint exists.
                - "continue but make it 5 days", "go ahead and use cheaper hotels", etc. is MODIFY.
                - A modification MUST preserve the previous requirements in effectiveRequest.
                - If the user changes one slot, keep all unrelated slots unchanged.
                - Do not invent dates, budgets, destinations, travelers or preferences.
                - HISTORY is only for an explicit history/recall request.
                - NEW_REQUEST is only for clearly unrelated work.

                Return JSON only in exactly this shape:
                {
                  "action":"RESUME|MODIFY|NEW_REQUEST|HISTORY",
                  "confidence":0.0,
                  "reason":"brief explanation",
                  "effectiveRequest":"complete requirement for the next execution pass"
                }
                """;

        String user = "PREVIOUS REQUIREMENT:\n" + safe(original)
                + "\n\nCURRENT EXECUTION STATE:\n" + state
                + "\n\nCURRENT USER TURN:\n" + current;
        try {
            StoppedRunActionDecision parsed = jsonSupport.read(
                    routedLlm.complete(AgentRole.PLANNER, system, user),
                    StoppedRunActionDecision.class).orElse(null);
            if (parsed != null) {
                String action = parsed.getAction();
                if ("RESUME".equals(action) || "MODIFY".equals(action)
                        || "NEW_REQUEST".equals(action) || "HISTORY".equals(action)) {
                    String effective = parsed.getEffectiveRequest();
                    if (effective.isBlank()) effective = original;
                    parsed.setEffectiveRequest(effective);
                    log.info("Stopped-run turn action={} confidence={} thread={} reason={} effectiveRequest={}",
                            action, parsed.getConfidence(),
                            checkpoint == null ? "" : checkpoint.graphThreadId(),
                            parsed.getReason(), effective);
                    return parsed;
                }
            }
        } catch (Exception ex) {
            log.warn("Stopped-run semantic turn resolution failed; preserving the saved request", ex);
        }
        return decision("RESUME", 0.0,
                "Resolver unavailable; preserve the existing stopped request", original);
    }

    private static String heuristicAction(String current) {
        String text = current.toLowerCase(Locale.ROOT);
        if (text.isBlank()) return null;
        boolean resumeLike = text.matches(".*\\b(continue|go ahead|proceed|keep going|resume|do it|yes|okay|ok)\\b.*")
                && !containsModificationSignal(text);
        if (resumeLike) return "RESUME";
        if (containsModificationSignal(text)) return "MODIFY";
        if (text.matches(".*\\b(history|show my previous|what did i ask|past trip|recent trips|remember|recall)\\b.*")) return "HISTORY";
        if (text.matches(".*\\b(new trip|different destination|start a new|plan for|book a different)\\b.*")) return "NEW_REQUEST";
        return null;
    }

    private static boolean containsModificationSignal(String text) {
        return text.matches(".*\\b(make it|change it|change to|prefer|instead|rather|also|add|remove|reduce|increase|cheaper|more affordable|direct flights|nonstop|longer|shorter|fewer|more|different|budget|days?|nights?|hotel|flight|origin|destination|dates?)\\b.*")
                || text.contains("make it")
                || text.contains("prefer direct")
                || text.contains("direct flights")
                || text.contains("cheaper hotels")
                || text.contains("change to ")
                || text.contains("make it ");
    }

    private static boolean isClearlyUnrelatedNewRequest(String original, String current) {
        if (original == null || original.isBlank()) return false;
        String originalText = original.toLowerCase(Locale.ROOT);
        String currentText = current.toLowerCase(Locale.ROOT);

        if (currentText.matches(".*\\b(new trip|different destination|different city|different country|start a new trip|plan a new trip|book a different trip|go somewhere else|another trip)\\b.*")) {
            return true;
        }

        String[] explicitDestinationSignals = new String[] {
                "to paris", "to london", "to tokyo", "to japan", "to singapore",
                "to bangkok", "to goa", "to delhi", "to mumbai", "to dubai",
                "trip to paris", "trip to london", "trip to tokyo"
        };
        for (String signal : explicitDestinationSignals) {
            if (currentText.contains(signal)) {
                if (!originalText.contains(signal.replace("to ", ""))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String heuristicEffectiveRequest(String original, String current, String action) {
        String cleanOriginal = safe(original);
        String cleanCurrent = current == null ? "" : current.trim();
        if ("RESUME".equals(action)) return cleanOriginal;
        if ("HISTORY".equals(action)) return cleanOriginal;
        if ("NEW_REQUEST".equals(action)) return cleanCurrent;
        if (cleanOriginal.isBlank()) return cleanCurrent;
        return cleanOriginal + " Also: " + cleanCurrent;
    }

    private static String checkpointSummary(TravelState checkpoint) {
        if (checkpoint == null) return "No checkpoint details available";
        StringBuilder b = new StringBuilder();
        b.append("requestType=").append(safe(checkpoint.requestType()));
        b.append("\norigin=").append(safe(checkpoint.origin()));
        b.append("\ndestination=").append(safe(checkpoint.destination()));
        b.append("\ndepartureDate=").append(safe(checkpoint.departureDate()));
        b.append("\nreturnDate=").append(safe(checkpoint.returnDate()));
        b.append("\ntravelers=").append(checkpoint.travelers());
        b.append("\nbudget=").append(checkpoint.budgetLabel());
        b.append("\ntravelStyle=").append(safe(checkpoint.travelStyle()));
        b.append("\nflightPreference=").append(safe(checkpoint.flightPreference()));
        if (checkpoint.agentPlan() != null) {
            b.append("\nTASKS=");
            checkpoint.agentPlan().getTasks().forEach(t -> b.append(t.getId()).append("=").append(t.getStatus()).append(";"));
        }
        return b.toString();
    }

    private static StoppedRunActionDecision decision(String action, double confidence, String reason, String effectiveRequest) {
        StoppedRunActionDecision d = new StoppedRunActionDecision();
        d.setAction(action);
        d.setConfidence(confidence);
        d.setReason(reason);
        d.setEffectiveRequest(effectiveRequest);
        return d;
    }

    private static String safe(Object value) { return value == null ? "" : String.valueOf(value); }
}
