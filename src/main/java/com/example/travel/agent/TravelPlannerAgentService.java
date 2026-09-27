package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties;
import com.example.travel.dto.AgentExecutionDetails;
import com.example.travel.dto.TripPlanResult;
import com.example.travel.dto.TravelPlanResponse;
import com.example.travel.dto.TravelRequest;
import com.example.travel.entity.UserPreference;
import com.example.travel.entity.AgentRunControl;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.ModificationRequest;
import com.example.travel.model.StoppedRunActionDecision;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.IntentPlan;
import com.example.travel.service.AgentExecutionBudget;
import com.example.travel.service.ConversationMemoryService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.GraphRunContext;
import com.example.travel.service.ModelRoutingContext;
import com.example.travel.service.TripPlanAssembler;
import com.example.travel.service.UserPreferenceService;
import com.example.travel.service.AgentRunAdmissionService;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.service.ApiRateLimitService;
import com.example.travel.exception.TooManyRequestsException;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.exception.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.state.StateSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Drives the LangGraph travel workflow.
 *
 * HITL approve/modify resume the same threadId from PostgreSQL checkpoints.
 * No in-memory pending map is used.
 */
@Service
public class TravelPlannerAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelPlannerAgentService.class);

    private final CompiledGraph<TravelState> travelGraph;
    private final RunnableConfig travelRunnableConfig;
    private final PostgresSaver travelCheckpointSaver;
    private final FinalPlannerAgentService finalPlannerAgentService;
    private final UserPreferenceService userPreferenceService;
    private final TravelModelsProperties travelModels;
    private final ModificationAgentService modificationAgentService;
    private final StoppedRunActionResolver stoppedRunActionResolver;
    private final AgentExecutionBudget executionBudget;
    private final GraphProgressHub graphProgressHub;
    private final GraphRunContext graphRunContext;
    private final ExecutorService travelPlanExecutor;
    private final AgentRunAdmissionService runAdmissionService;
    private final AgentRunControlService runControlService;
    private final ApiRateLimitService apiRateLimitService;
    private final ConversationMemoryService conversationMemoryService;
    private final TripPlanAssembler tripPlanAssembler;
    private final com.example.travel.service.TripHistoryService tripHistoryService;
    private final ObjectMapper objectMapper;
    private final int maxRetries;

    public TravelPlannerAgentService(
            CompiledGraph<TravelState> travelGraph,
            RunnableConfig travelRunnableConfig,
            PostgresSaver travelCheckpointSaver,
            FinalPlannerAgentService finalPlannerAgentService,
            UserPreferenceService userPreferenceService,
            TravelModelsProperties travelModels,
            ModificationAgentService modificationAgentService,
            StoppedRunActionResolver stoppedRunActionResolver,
            AgentExecutionBudget executionBudget,
            GraphProgressHub graphProgressHub,
            GraphRunContext graphRunContext,
            @org.springframework.beans.factory.annotation.Qualifier("travelPlanExecutor") ExecutorService travelPlanExecutor,
            AgentRunAdmissionService runAdmissionService,
            AgentRunControlService runControlService,
            ApiRateLimitService apiRateLimitService,
            ConversationMemoryService conversationMemoryService,
            TripPlanAssembler tripPlanAssembler,
            com.example.travel.service.TripHistoryService tripHistoryService,
            ObjectMapper objectMapper,
            @Value("${travel.graph.max-retries:2}") int maxRetries) {

        this.travelGraph = travelGraph;
        this.travelRunnableConfig = travelRunnableConfig;
        this.travelCheckpointSaver = travelCheckpointSaver;
        this.finalPlannerAgentService = finalPlannerAgentService;
        this.userPreferenceService = userPreferenceService;
        this.travelModels = travelModels;
        this.modificationAgentService = modificationAgentService;
        this.stoppedRunActionResolver = stoppedRunActionResolver;
        this.executionBudget = executionBudget;
        this.graphProgressHub = graphProgressHub;
        this.graphRunContext = graphRunContext;
        this.travelPlanExecutor = travelPlanExecutor;
        this.runAdmissionService = runAdmissionService;
        this.runControlService = runControlService;
        this.apiRateLimitService = apiRateLimitService;
        this.conversationMemoryService = conversationMemoryService;
        this.tripPlanAssembler = tripPlanAssembler;
        this.tripHistoryService = tripHistoryService;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
    }

    public TravelPlanResponse createTravelPlan(
            TravelRequest request,
            String historyContext) {

        String userId = TravelState.firstNonBlank(request.getUserId());
        if (userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }

        String threadId = userId + "-" + UUID.randomUUID();

        log.info(
                "Starting LangGraph travel orchestration for threadId={}",
                threadId);

        if (!apiRateLimitService.tryAcquire(userId)) {
            throw new TooManyRequestsException("Too many planning requests. Please wait a moment and try again.");
        }
        if (!runAdmissionService.tryAcquire(userId)) {
            throw new TooManyRequestsException("Your travel planning capacity is currently busy. Please wait for an active plan to finish.");
        }

        Map<String, Object> input = TravelState.fromRequest(request, historyContext);

        input.put(TravelState.MAX_RETRIES, maxRetries);
        input.put(TravelState.GRAPH_THREAD_ID, threadId);

        String policy = ModelRoutingContext.normalize(request.getSelectedModel());

        input.put(TravelState.SELECTED_MODEL, policy);
        input.put(TravelState.MODEL_POLICY, policy);

        userPreferenceService
                .find(userId)
                .ifPresent(preference -> applyPreferences(input, preference));

        ensureOrigin(input);

        RunnableConfig config = configFor(threadId);

        ModelRoutingContext.set(policy);
        executionBudget.begin();
        graphRunContext.open(
                threadId,
                executionBudget.capture(),
                policy);

        long runStarted = System.currentTimeMillis();
        GraphExecutionLogger.runStart(threadId, userId,
                String.valueOf(input.get(TravelState.REQUEST_TYPE)),
                String.valueOf(input.get(TravelState.DESTINATION)),
                String.valueOf(input.get(TravelState.ORIGIN)),
                String.valueOf(input.get(TravelState.DEPARTURE_DATE)),
                String.valueOf(input.get(TravelState.RETURN_DATE)),
                ((Number) input.getOrDefault(TravelState.TRAVELERS, 1)).intValue(),
                String.valueOf(input.get(TravelState.BUDGET_LABEL)), policy);
        try {
            travelGraph.invoke(input, config);
        } finally {
            graphRunContext.close(threadId);
            executionBudget.end();
            ModelRoutingContext.clear();
            runAdmissionService.release(userId);
        }

        TravelState state = requireCheckpointState(threadId);

        userPreferenceService.remember(
                userId,
                state.originIata(),
                state.travelStyle(),
                state.destination());

        boolean pending = isAwaitingHitl(threadId);

        GraphExecutionLogger.runComplete(state, System.currentTimeMillis() - runStarted, pending);
        log.info(
                "Graph paused for HITL={} threadId={}",
                pending,
                threadId);

        return toResponse(
                state,
                threadId,
                pending);
    }

    public String startTravelPlan(
            TravelRequest request,
            String historyContext) {

        String userId = TravelState.firstNonBlank(request.getUserId());
        if (userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }

        String threadId = userId + "-" + UUID.randomUUID();
        String conversationId = TravelState.firstNonBlank(request.getConversationId(), threadId);

        if (!apiRateLimitService.tryAcquire(userId)) {
            throw new TooManyRequestsException("Too many planning requests. Please wait a moment and try again.");
        }
        if (!runAdmissionService.tryAcquire(userId)) {
            throw new TooManyRequestsException("Your travel planning capacity is currently busy. Please wait for an active plan to finish.");
        }

        boolean handedOff = false;
        try {
            Map<String, Object> input = TravelState.fromRequest(request, historyContext);

            input.put(TravelState.MAX_RETRIES, maxRetries);
            input.put(TravelState.GRAPH_THREAD_ID, threadId);

            String policy = ModelRoutingContext.normalize(request.getSelectedModel());
            input.put(TravelState.SELECTED_MODEL, policy);
            input.put(TravelState.MODEL_POLICY, policy);

            userPreferenceService
                    .find(userId)
                    .ifPresent(preference -> applyPreferences(input, preference));

            ensureOrigin(input);
            graphProgressHub.open(threadId);

            String query = TravelState.firstNonBlank(
                    request.getOriginalPrompt(),
                    request.getPrompt(),
                    request.getPreferences());
            conversationMemoryService.saveMessage(userId, threadId, conversationId, "user", query);

            GraphExecutionLogger.runContext("plan-start", threadId, policy);
            runControlService.start(userId, conversationId, threadId, query, policy);

            Future<?> future = travelPlanExecutor.submit(() -> runStreaming(
                    threadId, userId, conversationId, policy, input));
            runControlService.registerFuture(threadId, future);
            handedOff = true;
        } catch (RejectedExecutionException ex) {
            runControlService.markFailed(threadId);
            throw new TooManyRequestsException("Travel planning is busy right now. Please try again shortly.");
        } finally {
            if (!handedOff) runAdmissionService.release(userId);
        }
        return threadId;
    }

    private void runStreaming(
            String threadId,
            String userId,
            String conversationId,
            String policy,
            Map<String, Object> input) {

        RunnableConfig config = configFor(threadId);

        ModelRoutingContext.set(policy);
        executionBudget.begin();

        graphRunContext.open(
                threadId,
                executionBudget.capture(),
                policy);

        long runStarted = System.currentTimeMillis();
        GraphExecutionLogger.runStart(threadId, userId,
                String.valueOf(input.get(TravelState.REQUEST_TYPE)),
                String.valueOf(input.get(TravelState.DESTINATION)),
                String.valueOf(input.get(TravelState.ORIGIN)),
                String.valueOf(input.get(TravelState.DEPARTURE_DATE)),
                String.valueOf(input.get(TravelState.RETURN_DATE)),
                ((Number) input.getOrDefault(TravelState.TRAVELERS, 1)).intValue(),
                String.valueOf(input.get(TravelState.BUDGET_LABEL)), policy);
        try {

            graphProgressHub.emit(
                    threadId,
                    "started",
                    Map.of(
                            "threadId",
                            threadId,
                            "node",
                            "START"));

            for (NodeOutput<TravelState> output : travelGraph.stream(input, config)) {

                GraphExecutionLogger.streamTransition(
                        threadId,
                        output.node(),
                        output.isEND());

                Map<String, Object> payload = new LinkedHashMap<>();

                payload.put("node", output.node());
                payload.put("end", output.isEND());

                if (output.state() != null) {
                    payload.put(
                            "pipeline",
                            output.state().pipeline());
                }

                graphProgressHub.emit(
                        threadId,
                        "node",
                        payload);
            }

            if (runControlService.isStopRequested(threadId)) {
                throw new GraphStopRequestedException();
            }
            TravelState state = requireCheckpointState(threadId);

            userPreferenceService.remember(
                    userId,
                    state.originIata(),
                    state.travelStyle(),
                    state.destination());

            boolean pending = isAwaitingHitl(threadId);

            TravelPlanResponse plan = toResponse(
                    state,
                    threadId,
                    pending,
                    "");

            conversationMemoryService.saveUiMessage(
                    userId,
                    threadId,
                    conversationId,
                    "assistant",
                    plan.getPlan() != null
                            && plan.getPlan().getTrip() != null
                                    ? TravelState.firstNonBlank(
                                            plan.getPlan().getTrip().getTitle(),
                                            "Plan ready")
                                    : "Plan ready",
                    plan);
            tripHistoryService.saveOrUpdate(userId, plan);

            GraphExecutionLogger.runComplete(state, System.currentTimeMillis() - runStarted, pending);

            Map<String, Object> done = new LinkedHashMap<>();

            done.put("plan", plan);

            graphProgressHub.emit(
                    threadId,
                    "complete",
                    done);
            runControlService.markCompleted(threadId);

        } catch (Exception ex) {

            if (runControlService.isStopRequested(threadId)) {
                TravelState checkpoint = null;
                try { checkpoint = requireCheckpointState(threadId); } catch (Exception ignored) {}
                Map<String, Object> stopped = new LinkedHashMap<>();
                stopped.put("threadId", threadId);
                stopped.put("message", "Stopped. Your current checkpoint is saved. Say Continue to resume this request.");
                if (checkpoint != null) stopped.put("pipeline", checkpoint.pipeline());
                graphProgressHub.emit(threadId, "stopped", stopped);
                runControlService.markStopped(threadId);
                log.info("Graph stopped threadId={} at persisted checkpoint", threadId);
            } else {
                GraphExecutionLogger.runFailed(threadId, System.currentTimeMillis() - runStarted, ex);
                runControlService.markFailed(threadId);
            log.warn(
                    "Streaming plan failed threadId={}",
                    threadId,
                    ex);

            // Never send exception messages, class names, or stack-trace details
            // to the browser. Full diagnostics stay in server logs only.
                graphProgressHub.emit(
                        threadId,
                        "failed",
                        Map.of(
                                "error",
                                "We couldn't complete your travel plan. Please try again."));
            }

        } finally {

            graphRunContext.close(threadId);
            executionBudget.end();
            ModelRoutingContext.clear();
            runAdmissionService.release(userId);
        }
    }

    /**
     * Requests a running graph to stop. The durable LangGraph checkpoint is the
     * source of truth; cancellation stops the orchestration thread and the next
     * Continue resumes the same threadId instead of creating a new requirement.
     */
    public boolean stop(String userId, String threadId) {
        String key = requireOwnedThread(userId, threadId);
        boolean requested = runControlService.requestStop(userId, key);
        if (requested) {
            try {
                TravelState checkpoint = requireCheckpointState(key);
                graphProgressHub.emit(key, "stop_requested", Map.of(
                        "threadId", key,
                        "message", "Stopping safely. The latest saved checkpoint will be resumed by Continue.",
                        "pipeline", checkpoint.pipeline()));
            } catch (Exception ignored) {
                graphProgressHub.emit(key, "stop_requested", Map.of(
                        "threadId", key,
                        "message", "Stopping safely. The latest saved checkpoint will be resumed by Continue."));
            }
        }
        return requested;
    }

    /**
     * Resolve arbitrary user input against the latest stopped checkpoint. A stopped
     * run is an existing execution context, not a fresh intent-classification turn.
     */
    public java.util.Optional<Map<String, Object>> tryHandleStoppedRunInput(
            String userId, String conversationId, String prompt, String historyContext) {
        return tryHandleStoppedRunInput(userId, conversationId, prompt, historyContext, "");
    }

    public java.util.Optional<Map<String, Object>> tryHandleStoppedRunInput(
            String userId, String conversationId, String prompt, String historyContext, String continuationThreadId) {
        var stopped = runControlService.findStoppedForConversation(userId, conversationId, continuationThreadId);
        if (stopped.isEmpty()) {
            var active = runControlService.findActiveForConversation(userId, conversationId, continuationThreadId);
            if (active.isPresent() && isContinueCommand(prompt)) {
                return java.util.Optional.of(attachToActiveRunInfo(active.get()));
            }
            return java.util.Optional.empty();
        }

        AgentRunControl run = stopped.get();
        String threadId = run.getThreadId();
        TravelState checkpoint = requireCheckpointState(threadId);
        StoppedRunActionDecision action = stoppedRunActionResolver.resolve(checkpoint, prompt);
        log.info("Stopped-run routing action={} confidence={} threadId={} prompt={}",
                action.getAction(), action.getConfidence(), threadId, prompt);

        return switch (action.getAction()) {
            case "RESUME" -> java.util.Optional.of(
                resumeLatestStoppedInfo(userId, conversationId, threadId));
            case "MODIFY" -> java.util.Optional.of(
                    startNaturalLanguageModification(userId, conversationId, threadId, prompt, action.getEffectiveRequest(), historyContext));
            default -> java.util.Optional.empty();
        };
    }

    private Map<String, Object> attachToActiveRunInfo(AgentRunControl run) {
        String threadId = run.getThreadId();
        TravelState checkpoint = requireCheckpointState(threadId);
        Map<String, String> taskStatuses = new LinkedHashMap<>();
        if (checkpoint.agentPlan() != null) {
            for (AgentTask task : checkpoint.agentPlan().getTasks()) {
                taskStatuses.put(task.getId(), task.getStatus().name());
            }
        }
        Map<String, Object> resumeState = new LinkedHashMap<>();
        resumeState.put("nextNode", travelGraph.getState(configFor(threadId)).next());
        resumeState.put("pipeline", checkpoint.pipeline());
        resumeState.put("requestType", checkpoint.requestType());
        resumeState.put("taskStatuses", taskStatuses);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("threadId", threadId);
        info.put("status", "ATTACHED");
        info.put("message", "This request is already running. Reconnecting to its progress…");
        info.put("resumeState", resumeState);
        info.put("resumeSinceEventId", graphProgressHub.latestEventId(threadId));
        return info;
    }

    private boolean isContinueCommand(String prompt) {
        if (prompt == null) return false;
        return prompt.trim().toLowerCase(java.util.Locale.ROOT)
                .matches("(?:please\\s+)?(?:continue|resume|go ahead|proceed|keep going|do it|yes|okay|ok)(?:\\s+please)?[.!?]*");
    }

    /** Backward-compatible explicit continuation entry point. */
    public java.util.Optional<Map<String, Object>> tryResumeLatestStopped(String userId, String conversationId, String prompt) {
        return tryHandleStoppedRunInput(userId, conversationId, prompt, "");
    }

    /**
     * Resumes the latest user-visible stopped run for this conversation. The
     * original USER_REQUEST lives inside the LangGraph checkpoint, so Continue
     * never becomes a new planning requirement.
     */
    public String resumeLatestStopped(String userId, String conversationId) {
        return String.valueOf(resumeLatestStoppedInfo(userId, conversationId).get("threadId"));
    }

    /**
     * Starts Continue from the persisted checkpoint and returns a small snapshot
     * of that checkpoint to the UI. The UI must render this snapshot first; it
     * must not reset the live card to Understand/Waiting just because a new SSE
     * subscription is being created.
     */
    public Map<String, Object> resumeLatestStoppedInfo(String userId, String conversationId) {
        return resumeLatestStoppedInfo(userId, conversationId, "");
    }

    private Map<String, Object> resumeLatestStoppedInfo(String userId, String conversationId, String preferredThreadId) {
        var stopped = runControlService.findStoppedForConversation(userId, conversationId, preferredThreadId);
        AgentRunControl stoppedRun = stopped
                .orElseThrow(() -> new IllegalStateException("There is no stopped request to continue."));
        String threadId = stoppedRun.getThreadId();
        if (!runAdmissionService.tryAcquire(userId)) {
            throw new TooManyRequestsException("Your travel planning capacity is currently busy. Please wait for the active run to finish.");
        }
        boolean handedOff = false;
        try {
            String policy = previousPolicy(threadId);
            TravelState checkpoint = requireCheckpointState(threadId);
            String nextNode = travelGraph.getState(configFor(threadId)).next();
            Map<String, String> taskStatuses = new LinkedHashMap<>();
            if (checkpoint.agentPlan() != null) {
                for (AgentTask task : checkpoint.agentPlan().getTasks()) {
                    taskStatuses.put(task.getId(), task.getStatus().name());
                }
            }
            Map<String, Object> resumeState = new LinkedHashMap<>();
            resumeState.put("nextNode", nextNode);
            resumeState.put("pipeline", checkpoint.pipeline());
            resumeState.put("requestType", checkpoint.requestType());
            resumeState.put("taskStatuses", taskStatuses);

            runControlService.start(userId, conversationId, threadId, stoppedRun.getOriginalRequest(), policy);
            graphProgressHub.open(threadId);

            // Capture the SSE cursor BEFORE submitting the resumed worker.
            // The worker can emit the RESUME/started event almost immediately;
            // capturing the cursor after submit creates a race where the cursor
            // points past the RESUME marker and the UI waits forever for the
            // current-run marker, eventually reporting a generic error.
            long resumeSinceEventId = graphProgressHub.latestEventId(threadId);

            // Publish the persisted checkpoint snapshot BEFORE starting the worker.
            // The browser can therefore hydrate the resumed card even if the worker
            // reaches the first graph node before the HTTP response/SSE connection
            // is fully established. The event is intentionally emitted after the
            // cursor is captured so it is guaranteed to be replayed by afterId.
            Map<String, Object> resumeEvent = new LinkedHashMap<>();
            resumeEvent.put("threadId", threadId);
            resumeEvent.put("node", "RESUME_STATE");
            resumeEvent.put("message", "Resuming from your saved checkpoint…");
            resumeEvent.putAll(resumeState);
            graphProgressHub.emit(threadId, "resume_state", resumeEvent);

            Future<?> future = travelPlanExecutor.submit(() -> runResumedStreaming(threadId, userId, conversationId, policy));
            runControlService.registerFuture(threadId, future);
            handedOff = true;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("threadId", threadId);
            info.put("status", "RESUMED");
            info.put("resumeState", resumeState);
            info.put("resumeSinceEventId", resumeSinceEventId);
            return info;
        } catch (RejectedExecutionException ex) {
            throw new TooManyRequestsException("Travel planning is busy right now. Please try again shortly.");
        } finally {
            if (!handedOff) runAdmissionService.release(userId);
        }
    }

    private void runResumedStreaming(String threadId, String userId, String conversationId, String policy) {
        RunnableConfig config = configFor(threadId);
        ModelRoutingContext.set(policy);
        executionBudget.begin();
        graphRunContext.open(threadId, executionBudget.capture(), policy);
        long runStarted = System.currentTimeMillis();
        try {
            TravelState checkpoint = requireCheckpointState(threadId);
            Map<String, Object> resumeInput = buildResumeInput(checkpoint,
                    runControlService.find(threadId).map(AgentRunControl::getOriginalRequest).orElse(checkpoint.userRequest()));

            graphProgressHub.emit(threadId, "started", Map.of("threadId", threadId, "node", "RESUME", "message", "Continuing your saved request…"));
            for (NodeOutput<TravelState> output : travelGraph.stream(GraphInput.resume(resumeInput), config)) {
                if (runControlService.isStopRequested(threadId)) {
                    throw new java.util.concurrent.CancellationException("User requested stop");
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("node", output.node());
                payload.put("end", output.isEND());
                if (output.state() != null) payload.put("pipeline", output.state().pipeline());
                graphProgressHub.emit(threadId, "node", payload);
            }
            if (runControlService.isStopRequested(threadId)) {
                throw new GraphStopRequestedException();
            }
            TravelState state = requireCheckpointState(threadId);
            boolean pending = isAwaitingHitl(threadId);
            TravelPlanResponse plan = toResponse(state, threadId, pending, "");
            conversationMemoryService.saveUiMessage(userId, threadId, conversationId, "assistant",
                    plan.getPlan() != null && plan.getPlan().getTrip() != null
                            ? TravelState.firstNonBlank(plan.getPlan().getTrip().getTitle(), "Plan ready")
                            : "Plan ready", plan);
            tripHistoryService.saveOrUpdate(userId, plan);
            graphProgressHub.emit(threadId, "complete", Map.of("plan", plan));
            runControlService.markCompleted(threadId);
            GraphExecutionLogger.runComplete(state, System.currentTimeMillis() - runStarted, pending);
        } catch (Exception ex) {
            if (runControlService.isStopRequested(threadId)) {
                runControlService.markStopped(threadId);
                graphProgressHub.emit(threadId, "stopped", Map.of("threadId", threadId, "message", "Stopped. Your saved request is ready to continue."));
            } else {
                runControlService.markFailed(threadId);
                GraphExecutionLogger.runFailed(threadId, System.currentTimeMillis() - runStarted, ex);
                graphProgressHub.emit(threadId, "failed", Map.of("error", "We couldn't continue your saved request. Please try again."));
            }
        } finally {
            graphRunContext.close(threadId);
            executionBudget.end();
            ModelRoutingContext.clear();
            runAdmissionService.release(userId);
        }
    }

    /**
     * Rehydrates a stopped checkpoint with the original trip contract.
     *
     * A stop can occur immediately after a specialist wave. If an older
     * checkpoint was created with a narrowed FLIGHT_SEARCH plan, blindly
     * resuming that checkpoint would finish after flights and incorrectly
     * treat "Continue" as a flight-only request. For a high-confidence
     * full-trip request (route + duration + trip-wide budget), restore the
     * canonical trip plan while preserving already successful task state.
     */
    private Map<String, Object> buildResumeInput(TravelState checkpoint, String originalRequest) {
        Map<String, Object> resume = new LinkedHashMap<>();
        String effective = TravelState.firstNonBlank(originalRequest, checkpoint.userRequest());
        resume.put(TravelState.USER_REQUEST, effective);

        // A durable checkpoint is the source of truth for execution progress.
        // Do not reconstruct the plan from the original prompt and do not reset
        // RUN_*/NEEDS_* flags: doing so can make already-completed specialists
        // execute again after Continue. LangGraph will resume at the persisted
        // next node and ProductionExecutionNode will execute only non-terminal
        // tasks in the checkpoint AgentPlan.
        if (checkpoint.agentPlan() != null) {
            resume.put(TravelState.AGENT_PLAN, checkpoint.agentPlan());
        }
        resume.put(TravelState.REQUEST_TYPE, checkpoint.requestType());
        resume.put(TravelState.NEEDS_FLIGHTS, checkpoint.needsFlights());
        resume.put(TravelState.NEEDS_HOTELS, checkpoint.needsHotels());
        resume.put(TravelState.NEEDS_RESEARCH, checkpoint.needsResearch());
        resume.put(TravelState.NEEDS_WEATHER, checkpoint.needsWeather());
        resume.put(TravelState.NEEDS_BUDGET, checkpoint.needsBudget());
        resume.put(TravelState.NEEDS_ITINERARY, checkpoint.needsItinerary());
        resume.put(TravelState.NEEDS_KNOWLEDGE, checkpoint.needsKnowledge());
        resume.put(TravelState.RUN_FLIGHTS, checkpoint.runFlights());
        resume.put(TravelState.RUN_HOTELS, checkpoint.runHotels());
        resume.put(TravelState.RUN_RESEARCH, checkpoint.runResearch());
        resume.put(TravelState.RUN_WEATHER, checkpoint.runWeather());
        resume.put(TravelState.RUN_BUDGET, checkpoint.runBudget());
        resume.put(TravelState.RUN_ITINERARY, checkpoint.runItinerary());
        resume.put(TravelState.PLAN_STRATEGY, checkpoint.planStrategy());
        resume.put(TravelState.PLAN_PRIORITY, checkpoint.planPriority());

        log.info("Resume preserves checkpoint threadId={} next work request={} tasks={}",
                checkpoint.graphThreadId(), effective,
                checkpoint.agentPlan() == null ? 0 : checkpoint.agentPlan().getTasks().stream()
                        .filter(t -> !t.terminal()).count());
        return resume;
    }

    private boolean isHighConfidenceFullTripRequest(String request) {
        if (request == null || request.isBlank()) return false;
        String text = request.toLowerCase(java.util.Locale.ROOT);
        return com.example.travel.support.TripSlotHeuristics.hasRouteHint(request)
                && com.example.travel.support.TripSlotHeuristics.hasDurationHint(request)
                && text.matches(".*(?:under|below|within|budget|₹|rs\\.?|inr|usd|\\$|\\u20ac|\\u00a3)\\s*.*");
    }

    /**
     * Rebuilds the latest structured response for a completed/recent thread.
     * This is used by Recent History when an older conversation-memory row
     * does not yet contain structuredData.
     */
    public TravelPlanResponse restore(String userId, String threadId) {
        String key = requireOwnedThread(userId, threadId);
        TravelState state = requireCheckpointState(key);
        return toResponse(state, key, isAwaitingHitl(key), "");
    }

    public TravelPlanResponse approve(
            String userId,
            String threadId) {

        String key = requireOwnedThread(
                userId,
                threadId);

        TravelState current = requireCheckpointState(key);
        if (current.goalEvaluation() == null
                || current.goalEvaluation().getStatus() != com.example.travel.model.GoalEvaluation.Status.ACHIEVED) {
            throw new IllegalStateException("This trip goal has not been achieved yet. Retry the failed capability before approving the plan.");
        }
        if (current.userInputRequired()) {
            throw new IllegalStateException("This plan is waiting for the missing travel details. Use Modify to provide them.");
        }

        RunnableConfig config = configFor(key);

        Map<String, Object> decision = new LinkedHashMap<>();

        decision.put(
                TravelState.HITL_DECISION,
                "approve");

        decision.put(
                TravelState.AWAITING_APPROVAL,
                Boolean.FALSE);

        ModelRoutingContext.set(
                previousPolicy(key));

        executionBudget.begin();

        graphRunContext.open(
                key,
                executionBudget.capture(),
                previousPolicy(key));

        TravelState state;

        try {

            state = travelGraph.invoke(
                    GraphInput.resume(decision),
                    config)
                    .orElseGet(
                            () -> requireCheckpointState(key));

        } finally {

            graphRunContext.close(key);
            executionBudget.end();
            ModelRoutingContext.clear();
        }

        try {

            travelCheckpointSaver.release(config);

        } catch (Exception ex) {

            log.warn(
                    "Could not release checkpoint threadId={}",
                    key,
                    ex);
        }

        TravelPlanResponse response = toResponse(
                state,
                key,
                false);

        String tips = finalPlannerAgentService.buildTips(state);

        response.getPlan().setTips(tips);

        response.setStatus("COMPLETE");
        response.setAwaitingApproval(false);
        response.getPlan().getTrip().setStatus("COMPLETE");
        response.getPlan().getTrip().setAwaitingApproval(false);
        tripHistoryService.saveOrUpdate(userId, response);

        return response;
    }

    /**
     * Applies a natural-language change to the stopped checkpoint asynchronously.
     * The same LangGraph thread and checkpoint are preserved.
     */
    private Map<String, Object> startNaturalLanguageModification(
            String userId, String conversationId, String threadId, String notes, String reframedRequest, String historyContext) {
        if (!runAdmissionService.tryAcquire(userId)) {
            throw new TooManyRequestsException("Your travel planning capacity is currently busy. Please wait for the active run to finish.");
        }
        boolean handedOff = false;
        try {
            TravelState checkpoint = requireCheckpointState(threadId);
            String policy = previousPolicy(threadId);
            runControlService.start(userId, conversationId, threadId,
                    TravelState.firstNonBlank(reframedRequest, checkpoint.userRequest(), notes), policy);
            graphProgressHub.open(threadId);
            long since = graphProgressHub.latestEventId(threadId);

            Map<String, String> statuses = new LinkedHashMap<>();
            if (checkpoint.agentPlan() != null) {
                for (AgentTask task : checkpoint.agentPlan().getTasks()) {
                    statuses.put(task.getId(), task.getStatus().name());
                }
            }
            Map<String, Object> resumeState = new LinkedHashMap<>();
            resumeState.put("nextNode", travelGraph.getState(configFor(threadId)).next());
            resumeState.put("pipeline", checkpoint.pipeline());
            resumeState.put("requestType", checkpoint.requestType());
            resumeState.put("taskStatuses", statuses);
            resumeState.put("action", "MODIFY");

            Map<String, Object> event = new LinkedHashMap<>(resumeState);
            event.put("threadId", threadId);
            event.put("node", "RESUME_STATE");
            event.put("message", "Applying your change to the saved request…");
            graphProgressHub.emit(threadId, "resume_state", event);

            Future<?> future = travelPlanExecutor.submit(() -> {
                try {
                    graphProgressHub.emit(threadId, "started", Map.of(
                            "threadId", threadId, "node", "MODIFY",
                            "message", "Understanding your change and updating the saved request…"));
                    modify(userId, threadId, notes, reframedRequest, historyContext);
                    if (runControlService.isStopRequested(threadId) || Thread.currentThread().isInterrupted()) {
                        throw new GraphStopRequestedException();
                    }
                    TravelState after = requireCheckpointState(threadId);
                    TravelPlanResponse response = toResponse(after, threadId, isAwaitingHitl(threadId));
                    graphProgressHub.emit(threadId, "complete", Map.of("plan", response));
                    runControlService.markCompleted(threadId);
                } catch (Exception ex) {
                    if (runControlService.isStopRequested(threadId)
                            || ex instanceof GraphStopRequestedException
                            || Thread.currentThread().isInterrupted()) {
                        runControlService.markStopped(threadId);
                        graphProgressHub.emit(threadId, "stopped", Map.of(
                                "threadId", threadId,
                                "message", "Stopped. Your updated checkpoint is saved and ready to continue."));
                        return;
                    }
                    log.error("Stopped-run modification failed userId={} threadId={}", userId, threadId, ex);
                    runControlService.markFailed(threadId);
                    graphProgressHub.emit(threadId, "failed", Map.of(
                            "error", "We couldn't apply that change. Please try again."));
                } finally {
                    runAdmissionService.release(userId);
                }
            });
            runControlService.registerFuture(threadId, future);
            handedOff = true;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("threadId", threadId);
            result.put("status", "MODIFYING");
            result.put("action", "MODIFY");
            result.put("resumeState", resumeState);
            result.put("resumeSinceEventId", since);
            result.put("userRequest", notes);
            return result;
        } catch (RejectedExecutionException ex) {
            throw new TooManyRequestsException("Travel planning is busy right now. Please try again shortly.");
        } finally {
            if (!handedOff) runAdmissionService.release(userId);
        }
    }

    /**
     * Resume an existing graph checkpoint with a user modification.
     *
     * IMPORTANT:
     * The checkpoint contains the original USER_REQUEST.
     * A modification is a new user request, so USER_REQUEST must
     * explicitly be replaced in the resumed graph state.
     */
    public TravelPlanResponse modify(
            String userId,
            String threadId,
            String notes,
            String historyContext) {
        return modify(userId, threadId, notes, "", historyContext);
    }

    /**
     * Applies a conversational turn to an existing checkpoint. The semantic turn
     * resolver supplies reframedRequest so the planner receives the complete
     * requirement (previous request + current change), while `notes` remains the
     * user's actual delta for ModificationAgentService.
     */
    public TravelPlanResponse modify(
            String userId,
            String threadId,
            String notes,
            String reframedRequest,
            String historyContext) {

        String key = requireOwnedThread(
                userId,
                threadId);

        TravelState previous = requireCheckpointState(key);

        RunnableConfig config = configFor(key);

        /*
         * Interpret the latest user modification using the
         * existing travel state as context.
         */
        ModificationRequest modification = modificationAgentService.interpret(
                previous,
                notes);

        String latestUserRequest;
        if (!TravelState.isBlank(reframedRequest)) {
            // The turn re-framer already merged the previous requirement with the
            // current user turn. Keep that complete contract for Planner/Replanner.
            latestUserRequest = reframedRequest;
        } else if (previous.userInputRequired()) {
            // A clarification answer such as "Bengaluru" is not a standalone
            // intent. Preserve the original goal and attach the user's missing
            // detail so the next planning pass can resolve the slot correctly.
            latestUserRequest = previous.userRequest()
                    + "\nUser supplied missing detail: "
                    + TravelState.firstNonBlank(notes, modification.getNotes());
        } else {
            latestUserRequest = TravelState.firstNonBlank(notes, modification.getNotes());
        }

        Map<String, Object> decision = new LinkedHashMap<>();

        decision.put(
                TravelState.HITL_DECISION,
                "modify");

        decision.put(
                TravelState.AWAITING_APPROVAL,
                Boolean.TRUE);
        decision.put(TravelState.USER_INPUT_REQUIRED, Boolean.FALSE);
        decision.put(TravelState.USER_INPUT_QUESTION, "");

        /*
         * ============================================================
         * CRITICAL FIX
         * ============================================================
         *
         * The graph is resumed using the existing checkpoint.
         *
         * Therefore TravelState.USER_REQUEST still contains the
         * ORIGINAL request unless we explicitly overwrite it here.
         *
         * ReplanAgentService uses state.userRequest() to understand
         * what the user is asking for now.
         *
         * Example:
         *
         * Original:
         * "best hotels in Mumbai under 20k budget"
         *
         * New:
         * "also include flight details from Bangalore to Mumbai"
         *
         * Without this update the ReplanAgent receives the old
         * hotel request and cannot intelligently select
         * GET_FLIGHT_DETAILS.
         */
        decision.put(
                TravelState.USER_REQUEST,
                latestUserRequest);

        /*
         * Keep REPLAN_NOTES as well because other parts of the
         * replan flow use this field for modification information.
         */
        decision.put(
                TravelState.REPLAN_NOTES,
                latestUserRequest);

        decision.put(
                TravelState.MODIFICATION,
                modification);

        /*
         * ADD DESTINATION
         */
        if (modification.isAddDestination()
                && !TravelState.isBlank(
                        modification.getDestination())) {

            decision.put(
                    TravelState.DESTINATION,
                    previous.destination()
                            + " and "
                            + modification.getDestination());

            decision.put(
                    TravelState.NEEDS_RESEARCH,
                    Boolean.TRUE);

            decision.put(
                    TravelState.NEEDS_ITINERARY,
                    Boolean.TRUE);
        }

        /*
         * HOTEL UPGRADE
         */
        if (modification.isHotelUpgrade()) {

            decision.put(
                    TravelState.HOTEL_CHEAPER,
                    Boolean.FALSE);
        }

        /*
         * REDUCE COST
         */
        if (modification.isReduceCost()) {

            decision.put(
                    TravelState.HOTEL_CHEAPER,
                    Boolean.TRUE);
        }

        /*
         * FLIGHT PREFERENCE
         */
        if (!TravelState.isBlank(
                modification.getFlightPreference())) {

            decision.put(
                    TravelState.FLIGHT_PREFERENCE,
                    modification.getFlightPreference());
        }

        /*
         * HOTEL / BUDGET
         */
        if (modification.getHotelBudget() != null) {
            // A hotel ceiling is a hotel-search constraint, not the overall trip
            // budget. Keep the original trip budget untouched so a modification
            // cannot accidentally turn "hotel under ₹X" into "trip under ₹X".
            decision.put(
                    TravelState.HOTEL_BUDGET,
                    modification.getHotelBudget());
        }

        /*
         * Optional conversation history.
         */
        if (!TravelState.isBlank(historyContext)) {

            decision.put(
                    TravelState.HISTORY_CONTEXT,
                    historyContext);
        }

        log.info(
                "Resuming graph for MODIFY type={} on same threadId={}",
                modification.getChangeType(),
                key);

        /*
         * This log is useful for verifying the fix.
         */
        log.info(
                "MODIFY USER REQUEST = [{}]",
                latestUserRequest);

        ModelRoutingContext.set(
                previous.modelPolicy());

        executionBudget.begin();

        graphRunContext.open(
                key,
                executionBudget.capture(),
                previous.modelPolicy());

        try {

            travelGraph.invoke(
                    GraphInput.resume(decision),
                    config);

        } finally {

            graphRunContext.close(key);
            executionBudget.end();
            ModelRoutingContext.clear();
        }

        TravelState state = requireCheckpointState(key);

        boolean pending = isAwaitingHitl(key);

        TravelPlanResponse response = toResponse(
                state,
                key,
                pending);
        tripHistoryService.saveOrUpdate(userId, response);
        return response;
    }

    /**
     * Human-triggered recovery of every currently failed required task on the
     * existing checkpoint. Successful work is preserved and dependency-aware
     * downstream work is reopened automatically by the recovery plan.
     */
    public TravelPlanResponse retryTask(String userId, String threadId, String taskId) {
        String key = requireOwnedThread(userId, threadId);
        TravelState current = requireCheckpointState(key);
        if (current.agentPlan() == null) {
            throw new IllegalStateException("This plan has no executable recovery plan.");
        }
        if (!current.awaitingApproval()) {
            throw new IllegalStateException("This plan is not waiting for a recovery decision.");
        }

        // The UI sends ALL_FAILED. Keep accepting a single task id for backward
        // compatibility with older clients, but the default recovery action is
        // always the complete failed-task set.
        String requested = taskId == null ? "" : taskId.trim().toLowerCase();
        List<String> failedTasks = current.agentPlan().failedRequiredTaskIds();

        List<String> recoveryTasks;
        if (requested.isBlank() || "all_failed".equals(requested) || "all-failed".equals(requested)
                || "all".equals(requested)) {
            recoveryTasks = failedTasks;
        } else {
            com.example.travel.model.AgentTask target = current.agentPlan().task(requested);
            if (target == null || !target.isRequired()
                    || target.getStatus() != com.example.travel.model.AgentTask.Status.FAILED) {
                throw new IllegalArgumentException("Unknown or non-failed retry task: " + taskId);
            }
            recoveryTasks = List.of(requested);
        }

        if (recoveryTasks.isEmpty()) {
            throw new IllegalStateException("There are no failed tasks to retry.");
        }

        RunnableConfig config = configFor(key);
        Map<String,Object> decision = new LinkedHashMap<>();
        decision.put(TravelState.HITL_DECISION, "modify");
        decision.put(TravelState.AWAITING_APPROVAL, Boolean.FALSE);
        decision.put(TravelState.RETRY_TASK, String.join(",", recoveryTasks));
        decision.put(TravelState.REPLAN_NOTES, "Manual recovery requested for failed tasks: " + recoveryTasks);

        String policy = previousPolicy(key);
        AgentRunControl existing = runControlService.find(key)
                .orElseThrow(() -> new IllegalStateException("No durable run control exists for thread " + key));

        // A retry is a real long-running execution. Put it back into RUNNING
        // state and execute it on the same cancellable plan executor used by
        // initial runs. Previously retryTask() called travelGraph.invoke()
        // directly on the HTTP request thread, so Stop had no Future to cancel.
        runControlService.start(
                userId,
                existing.getConversationId(),
                key,
                TravelState.firstNonBlank(existing.getOriginalRequest(), current.userRequest()),
                policy);

        ModelRoutingContext.set(policy);
        executionBudget.begin();
        graphRunContext.open(key, executionBudget.capture(), policy);
        graphProgressHub.emit(key, "started", Map.of(
                "threadId", key,
                "node", "RETRY",
                "message", "Retrying failed tasks"));

        boolean handedOff = false;
        try {
            Future<TravelPlanResponse> future = travelPlanExecutor.submit(() -> {
                try {
                    if (runControlService.isStopRequested(key)) {
                        throw new com.example.travel.exception.GraphStopRequestedException();
                    }

                    travelGraph.invoke(GraphInput.resume(decision), config);

                    if (runControlService.isStopRequested(key) || Thread.currentThread().isInterrupted()) {
                        throw new com.example.travel.exception.GraphStopRequestedException();
                    }

                    TravelState state = requireCheckpointState(key);
                    boolean pending = isAwaitingHitl(key);
                    TravelPlanResponse response = toResponse(state, key, pending);
                    tripHistoryService.saveOrUpdate(userId, response);
                    graphProgressHub.emit(key, "complete", Map.of("plan", response));
                    runControlService.markCompleted(key);
                    return response;
                } catch (Exception ex) {
                    if (runControlService.isStopRequested(key)
                            || ex instanceof com.example.travel.exception.GraphStopRequestedException
                            || Thread.currentThread().isInterrupted()) {
                        try {
                            runControlService.markStopped(key);
                        } finally {
                            graphProgressHub.emit(key, "stopped", Map.of(
                                    "threadId", key,
                                    "message", "Stopped. Your current checkpoint is saved. Say Continue to resume this request."));
                        }
                        throw ex instanceof RuntimeException re
                                ? re
                                : new RuntimeException(ex);
                    }
                    runControlService.markFailed(key);
                    graphProgressHub.emit(key, "failed", Map.of(
                            "error", "Retry could not complete. Please try again."));
                    throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
                }
            });
            runControlService.registerFuture(key, future);
            handedOff = true;

            try {
                return future.get();
            } catch (CancellationException ex) {
                // Future.cancel(true) is expected when the user presses Stop.
                // The worker owns the durable STOPPED transition; return the
                // latest checkpoint as a safe HTTP response rather than exposing
                // CancellationException to the browser.
                if (runControlService.isStopRequested(key)) {
                    TravelState checkpoint = requireCheckpointState(key);
                    TravelPlanResponse response = toResponse(checkpoint, key, isAwaitingHitl(key));
                    response.setStatus("STOPPED");
                    return response;
                }
                throw ex;
            } catch (InterruptedException ex) {
                // The HTTP/request thread itself may be interrupted while waiting
                // for the worker. Preserve the interrupt flag and treat it as a
                // user stop only when the durable run-control state confirms it.
                Thread.currentThread().interrupt();
                if (runControlService.isStopRequested(key)) {
                    TravelState checkpoint = requireCheckpointState(key);
                    TravelPlanResponse response = toResponse(checkpoint, key, isAwaitingHitl(key));
                    response.setStatus("STOPPED");
                    return response;
                }
                throw new RuntimeException("Retry execution was interrupted", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (runControlService.isStopRequested(key)) {
                    TravelState checkpoint = requireCheckpointState(key);
                    TravelPlanResponse response = toResponse(checkpoint, key, isAwaitingHitl(key));
                    response.setStatus("STOPPED");
                    return response;
                }
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            }
        } catch (RejectedExecutionException ex) {
            runControlService.markFailed(key);
            throw new TooManyRequestsException("Travel planning is busy right now. Please try again shortly.");
        } finally {
            if (!handedOff) {
                runControlService.markFailed(key);
            }
            graphRunContext.close(key);
            executionBudget.end();
            ModelRoutingContext.clear();
        }
    }

    public TravelPlanResponse reject(
            String userId,
            String threadId) {

        String key = requireOwnedThread(
                userId,
                threadId);

        requireCheckpointState(key);

        RunnableConfig config = configFor(key);

        Map<String, Object> decision = new LinkedHashMap<>();

        decision.put(
                TravelState.HITL_DECISION,
                "reject");

        decision.put(
                TravelState.AWAITING_APPROVAL,
                Boolean.FALSE);

        ModelRoutingContext.set(
                previousPolicy(key));

        executionBudget.begin();

        graphRunContext.open(
                key,
                executionBudget.capture(),
                previousPolicy(key));

        TravelState state;

        try {

            state = travelGraph.invoke(
                    GraphInput.resume(decision),
                    config)
                    .orElseGet(
                            () -> requireCheckpointState(key));

        } finally {

            graphRunContext.close(key);
            executionBudget.end();
            ModelRoutingContext.clear();
        }

        try {

            travelCheckpointSaver.release(config);

        } catch (Exception ex) {

            log.warn(
                    "Could not release checkpoint threadId={}",
                    key,
                    ex);
        }

        TravelPlanResponse response = toResponse(
                state,
                key,
                false);

        response.setStatus("REJECTED");
        response.setAwaitingApproval(false);
        response.getPlan().getTrip().setStatus("REJECTED");
        response.getPlan().getTrip().setAwaitingApproval(false);
        tripHistoryService.saveOrUpdate(userId, response);

        return response;
    }

    public Map<String, Object> history(
            String userId, String threadId) {

        String key = requireOwnedThread(userId, threadId);

        StateSnapshot<TravelState> snapshot = travelGraph.getState(
                configFor(key));

        TravelState state = snapshot.state();

        Map<String, Object> body = new LinkedHashMap<>();

        body.put(
                "threadId",
                key);

        body.put(
                "next",
                snapshot.next());

        body.put(
                "pipeline",
                state.pipeline());

        body.put(
                "modelPolicy",
                state.modelPolicy());

        body.put(
                "intentConfidence",
                state.intentConfidence());

        body.put("ragDecision", state.ragDecision());
        body.put("ragQuery", state.ragQuery());
        body.put("ragSources", state.ragSources());
        body.put("ragIterations", state.ragIterations());
        body.put("ragSufficient", state.ragSufficient());
        body.put("ragRetrievalMethod", state.ragRetrievalMethod());
        body.put("ragCandidateCount", state.ragCandidateCount());
        body.put("ragRerankedCount", state.ragRerankedCount());
        body.put("ragContextChars", state.ragContextChars());
        body.put("ragEvidenceScore", state.ragEvidenceScore());
        body.put("ragGroundedness", state.ragGroundedness());
        body.put("ragJudgePass", state.ragJudgePass());
        body.put("ragJudgeReason", state.ragJudgeReason());

        body.put(
                "validationErrors",
                state.validationErrors());

        body.put(
                "semanticNotes",
                state.semanticNotes());

        if (state.planQuality() != null) {

            body.put(
                    "planQuality",
                    state.planQuality());
        }

        if (state.nodeFailure() != null
                && !TravelState.isBlank(
                        state.nodeFailure().getLastFailedNode())) {

            body.put(
                    "nodeFailure",
                    state.nodeFailure());
        }

        body.put(
                "semanticNotes",
                state.semanticNotes());

        body.put(
                "executionTimeline",
                state.pipeline());

        try {

            List<Map<String, Object>> snapshots = new ArrayList<>();

            for (StateSnapshot<TravelState> item : travelGraph.getStateHistory(
                    configFor(key))) {

                Map<String, Object> row = new LinkedHashMap<>();

                row.put(
                        "node",
                        item.node());

                row.put(
                        "next",
                        item.next());

                if (item.state() != null) {

                    row.put(
                            "pipeline",
                            item.state().pipeline());

                    row.put(
                            "supervisorDecision",
                            item.state().supervisorDecision());

                    row.put(
                            "dispatchRoute",
                            item.state().dispatchRoute());

                    if (item.state().planQuality() != null) {

                        row.put(
                                "planQuality",
                                item.state()
                                        .planQuality()
                                        .getOverall());
                    }
                }

                snapshots.add(row);
            }

            body.put(
                    "snapshots",
                    snapshots);

        } catch (Exception ex) {

            log.debug(
                    "Could not load state history for threadId={}",
                    key,
                    ex);

            body.put(
                    "snapshots",
                    List.of());
        }

        return body;
    }

    public void assertOwnedThread(String userId, String threadId) {
        requireOwnedThread(userId, threadId);
    }

    private String requireOwnedThread(
            String userId,
            String threadId) {

        String key = TravelState.firstNonBlank(
                threadId,
                userId);

        String requester = TravelState.firstNonBlank(userId);
        if (requester.isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required");
        }

        if (!key.equals(requester)
                && !key.startsWith(requester + "-")) {

            throw new ResourceNotFoundException("Travel plan not found");
        }

        /*
         * SSE can connect immediately after /plan/start returns, while the
         * asynchronous LangGraph execution is still creating its first
         * checkpoint. Ownership must therefore be checked from the
         * server-generated thread id, not from graph state. Checkpoint-backed
         * authorization remains enforced by operations that actually read or
         * mutate graph state (approve/modify/reject/restore/history).
         */
        return key;
    }

    private TravelState requireCheckpointState(
            String threadId) {

        try {

            StateSnapshot<TravelState> snapshot = travelGraph.getState(
                    configFor(threadId));

            if (snapshot == null
                    || snapshot.state() == null) {

                throw new IllegalStateException(
                        "No graph checkpoint for threadId="
                                + threadId
                                + ". Generate a plan first "
                                + "(or the thread expired).");
            }

            return snapshot.state();

        } catch (IllegalStateException ex) {

            throw ex;

        } catch (Exception ex) {

            throw new IllegalStateException(
                    "No graph checkpoint for threadId="
                            + threadId
                            + ". Generate a plan first "
                            + "(checkpoints survive restart "
                            + "via PostgreSQL).",
                    ex);
        }
    }

    private String previousPolicy(
            String threadId) {

        try {

            return requireCheckpointState(
                    threadId).modelPolicy();

        } catch (Exception ignored) {

            return "BALANCED";
        }
    }

    private boolean isAwaitingHitl(
            String threadId) {

        try {

            StateSnapshot<TravelState> snapshot = travelGraph.getState(
                    configFor(threadId));

            if (snapshot == null) {
                return false;
            }

            TravelState checkpoint = snapshot.state();
            if (checkpoint == null || !checkpoint.awaitingApproval()) {
                return false;
            }

            // The persisted awaitingApproval flag is the authoritative
            // approval boundary. The finalization node sets it to TRUE before
            // the graph reaches the interrupt-before-HITL breakpoint, and the
            // approve/reject endpoints explicitly clear it before resuming.
            // Do not additionally infer approval from snapshot.next() or the
            // decision value: a PARTIAL provider result must still remain
            // pending human review.
            return checkpoint.awaitingApproval();

        } catch (Exception ex) {

            log.debug(
                    "Could not read HITL status for threadId={}",
                    threadId,
                    ex);

            return false;
        }
    }

    private void ensureOrigin(
            Map<String, Object> input) {

        if (!TravelState.isBlank(
                (String) input.get(
                        TravelState.ORIGIN))) {

            return;
        }

        String preferred = (String) input.get(
                TravelState.PREFERRED_AIRPORT);

        if (!TravelState.isBlank(preferred)) {

            input.put(
                    TravelState.ORIGIN,
                    preferred);

            return;
        }

        // Do not invent a departure city. Flight requests without an origin are
        // routed to the clarification gate instead of silently defaulting to BLR.
    }

    private void applyPreferences(
            Map<String, Object> input,
            UserPreference preference) {

        if (TravelState.isBlank(
                (String) input.get(
                        TravelState.ORIGIN))
                && preference.getPreferredAirport() != null) {

            input.put(
                    TravelState.ORIGIN,
                    preference.getPreferredAirport());

            input.put(
                    TravelState.PREFERRED_AIRPORT,
                    preference.getPreferredAirport());
        }

        if (preference.getTravelStyle() != null
                && TravelState.isBlank(
                        (String) input.get(
                                TravelState.TRAVEL_STYLE))) {

            input.put(
                    TravelState.TRAVEL_STYLE,
                    preference.getTravelStyle());
        }

        if (preference.getCurrency() != null) {

            input.put(
                    TravelState.CURRENCY,
                    preference.getCurrency());
        }

        String history = (String) input.getOrDefault(
                TravelState.HISTORY_CONTEXT,
                "");

        input.put(
                TravelState.HISTORY_CONTEXT,
                "Long-term preferences: airport="
                        + preference.getPreferredAirport()
                        + ", style="
                        + preference.getTravelStyle()
                        + ", currency="
                        + preference.getCurrency()
                        + ", lastDestination="
                        + preference.getLastDestination()
                        + "\n"
                        + history);
    }

    private RunnableConfig configFor(
            String threadId) {

        return RunnableConfig
                .builder(travelRunnableConfig)
                .threadId(threadId)
                .build();
    }

    private TravelPlanResponse toResponse(
            TravelState state,
            String threadId,
            boolean awaitingApproval) {

        return toResponse(
                state,
                threadId,
                awaitingApproval,
                "");
    }

    private TravelPlanResponse toResponse(
            TravelState state,
            String threadId,
            boolean awaitingApproval,
            String executionHistory) {

        String status;
        if (state.userInputRequired()) {
            status = "NEEDS_USER_INPUT";
        } else if (state.goalEvaluation() != null
                && state.goalEvaluation().getStatus() == com.example.travel.model.GoalEvaluation.Status.PARTIAL) {
            status = "PARTIAL";
        } else if (state.goalEvaluation() != null
                && state.goalEvaluation().getStatus() == com.example.travel.model.GoalEvaluation.Status.FAILED) {
            status = "FAILED";
        } else if (awaitingApproval) {
            status = "PENDING_APPROVAL";
        } else {
            status = "COMPLETE";
        }

        // A HISTORY request is a read from persistent trip memory. Return the
        // exact saved structured plan instead of running Planner/specialists again.
        if ("HISTORY".equalsIgnoreCase(state.requestType()) && !TravelState.isBlank(state.historyResult())) {
            try {
                TravelPlanResponse saved = objectMapper.readValue(
                        state.historyResult(), TravelPlanResponse.class);
                saved.setThreadId(threadId);
                saved.setUserId(state.userId());
                saved.setRequestType("HISTORY");
                saved.setTripPlanning(false);
                saved.setAwaitingApproval(false);
                saved.setApprovalState("NOT_REQUIRED");
                saved.setStatus("COMPLETE");
                if (saved.getPlan() != null && saved.getPlan().getTrip() != null) {
                    // A recalled plan is a read-only snapshot. Never expose the
                    // old HITL state or its approval controls on the new history
                    // conversation, otherwise the UI could send an approval to
                    // the wrong checkpoint/thread.
                    saved.getPlan().getTrip().setAwaitingApproval(false);
                    saved.getPlan().getTrip().setStatus("HISTORY");
                }
                return saved;
            } catch (Exception ex) {
                log.warn("Could not deserialize most recent saved trip for history request", ex);
            }
        }

        TripPlanResult plan = tripPlanAssembler.assemble(
                state,
                status,
                awaitingApproval);

        plan.setTips(
                TravelState.firstNonBlank(
                        state.finalTips(),
                        ""));

        AgentExecutionDetails execution = tripPlanAssembler.assembleExecution(
                state,
                executionHistory);

        TravelPlanResponse response = new TravelPlanResponse();

        response.setUserId(
                state.userId());

        response.setModel(
                TravelState.firstNonBlank(
                        state.modelPolicy(),
                        configuredModelsLabel()));

        response.setThreadId(
                threadId);

        response.setAwaitingApproval(
                awaitingApproval);
        response.setApprovalState(approvalState(state, awaitingApproval));
        if (state.goalEvaluation() != null) {
            response.setGoalStatus(state.goalEvaluation().getStatus().name());
            response.setUnmetCriteria(state.goalEvaluation().getUnmetCriteria());
            response.setBlockingIssues(state.goalEvaluation().getBlockingIssues());
        }
        List<String> failedTasks = state.agentPlan() == null ? List.of() : state.agentPlan().getTasks().stream()
                .filter(t -> t.isRequired() && t.getStatus() == com.example.travel.model.AgentTask.Status.FAILED)
                .map(com.example.travel.model.AgentTask::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        // The goal status is authoritative. A stale failed-task marker must never
        // keep the retry CTA alive after deterministic goal evaluation succeeded.
        response.setRetryableTasks(
                state.goalEvaluation() != null
                        && state.goalEvaluation().getStatus() == com.example.travel.model.GoalEvaluation.Status.ACHIEVED
                        ? List.of()
                        : failedTasks);
        response.setClarificationRequired(state.userInputRequired());
        response.setClarificationQuestion(state.userInputQuestion());

        response.setStatus(
                status);

        response.setRequestType(
                TravelState.firstNonBlank(state.requestType(), "GENERAL"));
        // Human approval is required only for an actual trip plan workflow.
        // Specialist lookups (weather, flights, hotels, research, budget) and
        // knowledge answers are informational and complete automatically.
        response.setTripPlanning(requiresTripPlanning(state));

        response.setPlan(
                plan);

        response.setExecution(
                execution);

        return response;
    }

    private boolean requiresTripPlanning(TravelState state) {
        return state != null && state.isTripPlanningWorkflow();
    }

    private String approvalState(TravelState state, boolean awaitingApproval) {
        if (state == null) return "NOT_REQUIRED";
        if (state.userInputRequired()) return "PENDING";
        if (awaitingApproval || state.awaitingApproval()) {
            return state.goalEvaluation() != null
                    && state.goalEvaluation().getStatus() == com.example.travel.model.GoalEvaluation.Status.ACHIEVED
                    ? "PENDING" : "ACTION_REQUIRED";
        }
        String decision = state.hitlDecision();
        if ("approve".equalsIgnoreCase(decision)
                && state.goalEvaluation() != null
                && state.goalEvaluation().getStatus() == com.example.travel.model.GoalEvaluation.Status.ACHIEVED) return "APPROVED";
        if ("reject".equalsIgnoreCase(decision)) return "REJECTED";
        return requiresTripPlanning(state) ? "ACTION_REQUIRED" : "NOT_REQUIRED";
    }

    private String configuredModelsLabel() {

        return "planner="
                + travelModels.getPlanner()
                + "; extract="
                + travelModels.getExtraction()
                + "; itinerary="
                + travelModels.getItinerary()
                + "; final="
                + travelModels.getFinale();
    }
}