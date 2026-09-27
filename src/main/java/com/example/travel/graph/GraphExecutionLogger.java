package com.example.travel.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.travel.model.AgentDecision;
import com.example.travel.model.NodeFailureInfo;

import java.util.List;
import java.util.Map;

/**
 * Structured logs for LangGraph orchestration, routing, quality, and replanning.
 */
public final class GraphExecutionLogger {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutionLogger.class);

    private GraphExecutionLogger() {
    }


    public static void runStart(String threadId, String userId, String goal, String destination,
                                String origin, String departureDate, String returnDate, int travelers,
                                String budget, String policy) {
        log.info("[e2e] phase=run-start threadId={} userId={} goal={} origin={} destination={} departureDate={} returnDate={} travelers={} budget={} modelPolicy={}",
                safe(threadId), safe(userId), safe(goal), safe(origin), safe(destination),
                safe(departureDate), safe(returnDate), travelers, safe(budget), safe(policy));
    }

    public static void runComplete(TravelState state, long durationMs, boolean awaitingHitl) {
        log.info("[e2e] phase=run-complete threadId={} durationMs={} awaitingHitl={} status={} tasks={} results=[flights={},hotels={},research={},weather={},budget={},itinerary={}]",
                threadId(state), durationMs, awaitingHitl, stateStatus(state), taskSummary(state),
                state.flights().size(), state.hotels().size(), state.research().size(),
                state.weather() == null ? 0 : 1, state.budgetSummary() == null ? 0 : 1,
                state.itinerary() == null ? 0 : 1);
    }

    public static void runFailed(String threadId, long durationMs, Throwable error) {
        log.error("[e2e] phase=run-failed threadId={} durationMs={} errorType={} message={}",
                safe(threadId), durationMs, error == null ? "unknown" : error.getClass().getSimpleName(),
                safe(error == null ? null : error.getMessage()));
    }

    public static void stageState(String node, TravelState state, String phase) {
        log.info("[e2e] phase=stage-state node={} threadId={} requestType={} retry={}/{} tasks={} capabilities=[flights={},hotels={},research={},weather={},budget={},itinerary={},knowledge={}] results=[flights={},hotels={},research={},weather={},budget={},itinerary={}]",
                node, threadId(state), state.requestType(), state.retryCount(), state.maxRetries(), taskSummary(state),
                state.runFlights(), state.runHotels(), state.runResearch(), state.runWeather(), state.runBudget(),
                state.runItinerary(), state.needsKnowledge(), state.flights().size(), state.hotels().size(),
                state.research().size(), state.weather() == null ? 0 : 1, state.budgetSummary() == null ? 0 : 1,
                state.itinerary() == null ? 0 : 1);
    }

    public static void llmStage(String node, TravelState state, List<?> calls, long durationMs) {
        log.info("[e2e] phase=llm node={} threadId={} calls={} durationMs={}", node, threadId(state), calls == null ? 0 : calls.size(), durationMs);
    }

    public static void stageDecision(String node, TravelState state, String decision, String reason) {
        log.info("[e2e] phase=decision node={} threadId={} decision={} reason={} route={} supervisor={} retry={}/{}",
                node, threadId(state), safe(decision), safe(reason), safe(state.dispatchRoute()),
                safe(state.supervisorDecision()), state.retryCount(), state.maxRetries());
    }

    private static String taskSummary(TravelState state) {
        if (state == null || state.agentPlan() == null || state.agentPlan().getTasks() == null) return "-";
        return state.agentPlan().getTasks().stream()
                .map(t -> t.getId() + ":" + t.getStatus() + ":a" + t.getAttempts())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String stateStatus(TravelState state) {
        if (state == null) return "null";
        if (state.awaitingApproval()) return "AWAITING_HITL";
        if (state.supervisorDecision() != null && !state.supervisorDecision().isBlank()) return state.supervisorDecision();
        return "RUNNING";
    }

    private static String safe(String value) {
        if (value == null) return "-";
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > 180 ? normalized.substring(0, 177) + "..." : normalized;
    }

    public static void nodeStart(String node, TravelState state) {
        String retryReason = null;
        if (state.retryCount() > 0) {
            NodeFailureInfo failure = state.nodeFailure();
            if (!TravelState.isBlank(failure.getLastError())) {
                retryReason = failure.getLastFailedNode() + ":" + failure.getLastError();
            } else {
                AgentDecision lastDecision = state.lastDecision();
                if (!TravelState.isBlank(lastDecision.getReason())) {
                    retryReason = lastDecision.getReason();
                }
            }
        }

        if (retryReason != null) {
            log.info("[graph] node={} phase=start threadId={} retry={}/{} retryReason={} needs=[flight={},hotel={},research={},weather={},budget={},itinerary={}]",
                    node, threadId(state), state.retryCount(), state.maxRetries(), retryReason,
                    state.needsFlights(), state.needsHotels(), state.needsResearch(),
                    state.needsWeather(), state.needsBudget(), state.needsItinerary());
        } else {
            log.info("[graph] node={} phase=start threadId={} retry={}/{} needs=[flight={},hotel={},research={},weather={},budget={},itinerary={}]",
                    node, threadId(state), state.retryCount(), state.maxRetries(),
                    state.needsFlights(), state.needsHotels(), state.needsResearch(),
                    state.needsWeather(), state.needsBudget(), state.needsItinerary());
        }
    }

    public static void nodeComplete(String node, TravelState state, long durationMs) {
        log.info("[graph] node={} phase=complete threadId={} durationMs={}",
                node, threadId(state), durationMs);
    }

    public static void nodeFailed(String node, TravelState state, long durationMs, String error) {
        log.warn("[graph] node={} phase=failed threadId={} retry={}/{} durationMs={} error={}",
                node, threadId(state), state.retryCount(), state.maxRetries(), durationMs, error);
    }

    public static void route(String from, String to, TravelState state, String reason) {
        log.info("[graph] route from={} to={} threadId={} reason={} specialists={}",
                from, to, threadId(state), reason, SpecialistRouter.plannedSpecialists(state));
    }

    public static void parallelFanOut(TravelState state) {
        log.info("[graph] parallel-fan-out threadId={} specialists={}",
                threadId(state), SpecialistRouter.plannedSpecialists(state));
    }

    public static void specialistResult(String node, TravelState state, String status, String summary) {
        log.info("[graph] specialist={} threadId={} status={} {}",
                node, threadId(state), status, summary);
    }

    public static void nodeFailure(String node, TravelState state, String error, boolean retryable, int attempt) {
        log.warn("[graph] node-failure node={} threadId={} retryable={} attempt={} error={}",
                node, threadId(state), retryable, attempt, error);
    }

    public static void supervisorDecision(TravelState state, String decision, double qualityHint, String reason) {
        log.info("[graph] supervisor threadId={} decision={} qualityHint={} flights={} hotels={} research={} reason={}",
                threadId(state),
                decision,
                qualityHint,
                state.flights().size(),
                state.hotels().size(),
                state.research().size(),
                reason);
    }

    public static void validation(TravelState state, double overall, boolean pass, List<String> errors, List<String> semantic) {
        log.info("[graph] validator threadId={} overall={} pass={} errors={} semantic={}",
                threadId(state),
                String.format("%.2f", overall),
                pass,
                errors.size(),
                semantic.size());
        if (!errors.isEmpty()) {
            log.info("[graph] validator-errors threadId={} {}", threadId(state), errors);
        }
        if (!semantic.isEmpty()) {
            log.info("[graph] validator-semantic threadId={} {}", threadId(state), semantic);
        }
    }

    public static void semanticValidation(TravelState state, String status, double score, List<String> issues) {
        log.info("[graph] semantic threadId={} status={} score={} issues={}",
                threadId(state), status, String.format("%.2f", score), issues.size());
    }

    public static void replan(TravelState state, List<?> actions, Map<String, Object> selectiveNeeds) {
        log.info("[graph] replan threadId={} retry={}/{} actions={} selective={}",
                threadId(state),
                state.retryCount() + 1,
                state.maxRetries(),
                actions,
                selectiveNeeds);
    }

    public static void hitl(TravelState state, String decision, boolean awaiting) {
        log.info("[graph] hitl threadId={} decision={} awaitingApproval={}",
                threadId(state), decision, awaiting);
    }

    public static void streamTransition(String threadId, String node, boolean end) {
        log.info("[graph] stream threadId={} node={} end={}", threadId, node, end);
    }

    public static void runContext(String phase, String threadId, String policy) {
        log.info("[graph] context phase={} threadId={} policy={}", phase, threadId, policy);
    }

    private static String threadId(TravelState state) {
        return TravelState.isBlank(state.graphThreadId()) ? "-" : state.graphThreadId();
    }
}
