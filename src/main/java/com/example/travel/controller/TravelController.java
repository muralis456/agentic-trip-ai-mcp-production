package com.example.travel.controller;

import com.example.travel.agent.TravelPlannerAgentService;
import com.example.travel.dto.PlanDecisionRequest;
import com.example.travel.dto.TravelPlanResponse;
import com.example.travel.dto.TravelRequest;
import com.example.travel.service.ConversationMemoryService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.QueryNormalizationService;
import com.example.travel.service.TripHistoryService;
import com.example.travel.idempotency.IdempotencyService;
import com.example.travel.idempotency.IdempotencyService.DuplicateRequestException;
import com.example.travel.idempotency.IdempotencyService.KeyReuseException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TravelController {
    private static final Logger log = LoggerFactory.getLogger(TravelController.class);

    private final TravelPlannerAgentService travelPlannerAgentService;
    private final ConversationMemoryService conversationMemoryService;
    private final GraphProgressHub graphProgressHub;
    private final TripHistoryService tripHistoryService;
    private final QueryNormalizationService queryNormalizationService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public TravelController(TravelPlannerAgentService travelPlannerAgentService,
                            ConversationMemoryService conversationMemoryService,
                            GraphProgressHub graphProgressHub,
                            TripHistoryService tripHistoryService,
                            QueryNormalizationService queryNormalizationService,
                            IdempotencyService idempotencyService,
                            ObjectMapper objectMapper) {
        this.travelPlannerAgentService = travelPlannerAgentService;
        this.conversationMemoryService = conversationMemoryService;
        this.graphProgressHub = graphProgressHub;
        this.tripHistoryService = tripHistoryService;
        this.queryNormalizationService = queryNormalizationService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/plan")
    public ResponseEntity<TravelPlanResponse> createPlan(Authentication authentication,
                                                         @Valid @RequestBody TravelRequest request) {
        String userId = currentUser(authentication);
        request.setUserId(userId);
        String sessionId = userId;
        String query = request.getPrompt() != null ? request.getPrompt() : request.getPreferences();
        request.setOriginalPrompt(query);
        QueryNormalizationService.NormalizationResult normalization = queryNormalizationService.normalize(request);
        if (!normalization.normalizedPrompt().isBlank()) request.setPrompt(normalization.normalizedPrompt());
        log.info("Received travel plan request destination={} normalizedQuery={} corrections={}",
                request.getDestination(), normalization.normalizedPrompt(), normalization.corrections());

        conversationMemoryService.saveMessage(userId, sessionId, "user", query);
        String historyContext = conversationMemoryService.buildHistoryContext(userId, sessionId);
        try {
            TravelPlanResponse response = travelPlannerAgentService.createTravelPlan(request, historyContext);
            conversationMemoryService.saveUiMessage(userId, response.getThreadId(), "assistant",
                    responseMessage(response, "Plan ready"), response);
            tripHistoryService.saveOrUpdate(userId, response);
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            conversationMemoryService.saveMessage(userId, sessionId, "assistant",
                    "Plan failed: " + safeError(ex));
            throw ex;
        }
    }

    @PostMapping("/plan/start")
    public ResponseEntity<Map<String, Object>> startPlan(Authentication authentication,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                         @Valid @RequestBody TravelRequest request) {
        String userId = currentUser(authentication);
        request.setUserId(userId);
        String requestHash = requestHash(request);
        if (idempotencyKey != null) {
            try {
                var existing = idempotencyService.find(userId, idempotencyKey, requestHash);
                if (existing.isPresent()) {
                    return ResponseEntity.status(HttpStatus.ACCEPTED).body(objectMapper.readValue(existing.get(), Map.class));
                }
                idempotencyService.claim(userId, idempotencyKey, requestHash);
            } catch (DuplicateRequestException duplicate) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "IN_PROGRESS", "message", duplicate.getMessage()));
            } catch (KeyReuseException conflict) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "KEY_REUSED", "message", conflict.getMessage()));
            } catch (Exception ex) {
                log.error("Idempotency persistence failed userId={} key={} hash={}", userId, idempotencyKey, requestHash, ex);
                throw new IllegalStateException("Unable to process Idempotency-Key. Verify Flyway migration V8__phase1_idempotency.sql is applied.", ex);
            }
        }
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "conversation-" + java.util.UUID.randomUUID();
            request.setConversationId(conversationId);
        }

        // Keep the RAW current-turn prompt as the authority for dates. A stale
        // hidden date from the UI must not be mistaken for a user-entered date.
        // In particular, do not allow yesterday's date to survive into a live
        // flight search when the current prompt contains no explicit date.
        String rawPrompt = request.getPrompt();

        // A stopped run is a durable continuation point. For an explicit
        // continuation command, resume that same LangGraph thread instead of
        // creating a new thread and asking the intent/planner agents to guess
        // what the user meant. The original requirement remains in the
        // checkpoint's USER_REQUEST.
        String historyContext = conversationMemoryService.buildConversationHistoryContext(userId, conversationId);
        java.util.Optional<Map<String, Object>> resumed = travelPlannerAgentService
            .tryHandleStoppedRunInput(userId, conversationId, rawPrompt, historyContext,
                request.getContinuationThreadId());
        if (resumed.isPresent()) {
            Map<String, Object> resumeInfo = new java.util.LinkedHashMap<>(resumed.get());
            String threadId = String.valueOf(resumeInfo.get("threadId"));
            // The resume service captures the cursor BEFORE it submits the worker.
            // Re-capturing it here would be too late and could skip the RESUME
            // marker if the worker emits it immediately.
            conversationMemoryService.saveMessage(userId, threadId, conversationId, "user", rawPrompt);
            log.info("Stopped-run input routed action={} threadId={} conversationId={} prompt={} resumeSinceEventId={} resumeState={}",
                    resumeInfo.getOrDefault("action", resumeInfo.get("status")), threadId, conversationId, rawPrompt,
                    resumeInfo.get("resumeSinceEventId"), resumeInfo.get("resumeState"));
            if (idempotencyKey != null) {
                try {
                    idempotencyService.complete(userId, idempotencyKey, requestHash,
                            objectMapper.writeValueAsString(resumeInfo));
                } catch (Exception ex) {
                    log.warn("Unable to persist stopped-run idempotency response", ex);
                }
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(resumeInfo);
        }

        if (request.getDepartureDate() != null && !request.getDepartureDate().isBlank()
                && !com.example.travel.support.TripSlotHeuristics.hasDateHint(rawPrompt)
                && isPastDate(request.getDepartureDate())) {
            log.warn("Clearing stale past departureDate={} because current prompt has no explicit date",
                    request.getDepartureDate());
            request.setDepartureDate("");
        }
        if (request.getReturnDate() != null && !request.getReturnDate().isBlank()
                && !com.example.travel.support.TripSlotHeuristics.hasDateHint(rawPrompt)
                && isPastDate(request.getReturnDate())) {
            request.setReturnDate("");
        }
        conversationMemoryService.hydrateRequestFromConversation(userId, conversationId, request);
        request.setOriginalPrompt(rawPrompt);
        QueryNormalizationService.NormalizationResult normalization = queryNormalizationService.normalize(request);
        if (!normalization.normalizedPrompt().isBlank()) request.setPrompt(normalization.normalizedPrompt());
        log.info("Query normalization conversationId={} normalizedQuery={} corrections={} entities={}",
                conversationId, normalization.normalizedPrompt(), normalization.corrections(), normalization.entities());

        String threadId = travelPlannerAgentService.startTravelPlan(request, historyContext);
        Map<String, Object> started = Map.of("threadId", threadId, "status", "STARTED");
        if (idempotencyKey != null) {
            try { idempotencyService.complete(userId, idempotencyKey, requestHash, objectMapper.writeValueAsString(started)); }
            catch (Exception ex) { log.warn("Unable to persist idempotency response", ex); }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    }

    @GetMapping(value = "/plan/{threadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter planEvents(Authentication authentication, @PathVariable String threadId,
                                 @RequestParam(defaultValue = "false") boolean liveOnly,
                                 @RequestParam(defaultValue = "-1") long afterId) {
        String userId = currentUser(authentication);
        travelPlannerAgentService.assertOwnedThread(userId, threadId);
        if (afterId >= 0) {
            return graphProgressHub.subscribe(threadId, afterId);
        }
        return graphProgressHub.subscribe(threadId, liveOnly);
    }

    @GetMapping("/chat/history")
    public ResponseEntity<?> chatHistory(Authentication authentication,
                                         @RequestParam(defaultValue = "40") int limit) {
        String userId = currentUser(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(conversationMemoryService.getHistoryForUi(userId, limit));
    }

    @PostMapping("/plan/approve")
    public ResponseEntity<TravelPlanResponse> approve(Authentication authentication,
                                                      @RequestBody PlanDecisionRequest request) {
        String userId = currentUser(authentication);
        request.setUserId(userId);
        requireThread(request.getThreadId());
        TravelPlanResponse response = travelPlannerAgentService.approve(userId, request.getThreadId());
        conversationMemoryService.saveUiMessage(userId, request.getThreadId(), "assistant", responseMessage(response, "Plan approved"), response);
        tripHistoryService.saveOrUpdate(userId, response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/plan/modify")
    public ResponseEntity<TravelPlanResponse> modify(Authentication authentication,
                                                      @RequestBody PlanDecisionRequest request) {
        String userId = currentUser(authentication);
        request.setUserId(userId);
        requireThread(request.getThreadId());
        conversationMemoryService.saveMessage(userId, request.getThreadId(), "user",
                "Modify: " + (request.getNotes() == null ? "(no details)" : request.getNotes()));
        String historyContext = conversationMemoryService.buildHistoryContext(userId, request.getThreadId());
        try {
            TravelPlanResponse response = travelPlannerAgentService.modify(userId, request.getThreadId(),
                    request.getNotes() == null ? "Please adjust the plan" : request.getNotes(), historyContext);
            conversationMemoryService.saveUiMessage(userId, request.getThreadId(), "assistant",
                    responseMessage(response, "Modified plan ready"), response);
            tripHistoryService.saveOrUpdate(userId, response);
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            conversationMemoryService.saveMessage(userId, request.getThreadId(), "assistant", "Modify failed: " + safeError(ex));
            throw ex;
        }
    }

    @PostMapping("/plan/{threadId}/stop")
    public ResponseEntity<Map<String, Object>> stop(Authentication authentication, @PathVariable String threadId) {
        String userId = currentUser(authentication);
        boolean stopped = travelPlannerAgentService.stop(userId, threadId);
        return ResponseEntity.ok(Map.of(
                "threadId", threadId,
                "status", stopped ? "STOP_REQUESTED" : "NOT_RUNNING",
                "message", stopped
                        ? "Stopping safely. The latest saved checkpoint will be available to Continue."
                        : "This run is no longer running."));
    }

    @PostMapping("/plan/retry")
    public ResponseEntity<TravelPlanResponse> retry(Authentication authentication,
                                                    @RequestBody PlanDecisionRequest request) {
        String userId = currentUser(authentication);
        request.setUserId(userId);
        requireThread(request.getThreadId());
        if (request.getNotes() == null || request.getNotes().isBlank()) {
            throw new IllegalArgumentException("Retry task is required");
        }
        log.info("Retry request received userId={} threadId={} task={}",
                userId, request.getThreadId(), request.getNotes());
        TravelPlanResponse response = travelPlannerAgentService.retryTask(userId, request.getThreadId(), request.getNotes());
        conversationMemoryService.saveUiMessage(userId, request.getThreadId(), "assistant",
                responseMessage(response, "Retry completed"), response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/plan/reject")
    public ResponseEntity<TravelPlanResponse> reject(Authentication authentication,
                                                      @RequestBody PlanDecisionRequest request) {
        String userId = currentUser(authentication);
        request.setUserId(userId);
        requireThread(request.getThreadId());
        TravelPlanResponse response = travelPlannerAgentService.reject(userId, request.getThreadId());
        conversationMemoryService.saveUiMessage(userId, request.getThreadId(), "assistant", responseMessage(response, "Plan rejected"), response);
        tripHistoryService.saveOrUpdate(userId, response);
        return ResponseEntity.ok(response);
    }

    private String requestHash(TravelRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) { throw new IllegalStateException("Unable to hash travel request", ex); }
    }

    private String responseMessage(TravelPlanResponse response, String fallback) {
        if (response == null || response.getPlan() == null) return fallback;
        String tips = response.getPlan().getTips();
        if (tips != null && !tips.isBlank()) return tips;
        if (response.getPlan().getItinerary() != null && !response.getPlan().getItinerary().isEmpty()) {
            return response.getPlan().getItinerary().toDisplay();
        }
        return fallback;
    }

    @GetMapping("/trips")
    public ResponseEntity<?> trips(Authentication authentication, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tripHistoryService.list(currentUser(authentication), limit));
    }

    @GetMapping("/trips/{id}")
    public ResponseEntity<?> trip(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tripHistoryService.getPlan(currentUser(authentication), id));
    }

    @DeleteMapping("/history")
    public ResponseEntity<?> deleteHistory(Authentication authentication,
                                           @RequestParam(required = false) String sessionId,
                                           @RequestParam(required = false) String conversationId,
                                           @RequestParam(required = false) Long tripId) {
        String owner = currentUser(authentication);
        if (tripId != null) {
            boolean deleted = tripHistoryService.deleteTripAndMemory(owner, tripId);
            if (!deleted) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("deleted", true, "type", "trip"));
        }
        if (conversationId != null && !conversationId.isBlank()) {
            long deletedRows = conversationMemoryService.deleteConversation(owner, conversationId);
            return ResponseEntity.ok(Map.of("deleted", true, "type", "conversation", "memoryRows", deletedRows));
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "History session is required"));
        }
        long deletedRows = conversationMemoryService.deleteSession(owner, sessionId);
        return ResponseEntity.ok(Map.of("deleted", true, "type", "conversation", "memoryRows", deletedRows));
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<?> chatSession(Authentication authentication, @PathVariable String sessionId,
                                         @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(conversationMemoryService.getHistoryForSessionUi(currentUser(authentication), sessionId, limit));
    }

    @GetMapping("/chat/conversation/{conversationId}")
    public ResponseEntity<?> chatConversation(Authentication authentication, @PathVariable String conversationId,
                                              @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(conversationMemoryService.getHistoryForConversationUi(currentUser(authentication), conversationId, limit));
    }

    @GetMapping("/chat/message/{id}")
    public ResponseEntity<?> chatMessage(Authentication authentication, @PathVariable Long id) {
        return conversationMemoryService.getMessageForUi(currentUser(authentication), id)
                .map(message -> ResponseEntity.ok(message))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/plan/{threadId}/restore")
    public ResponseEntity<?> restorePlan(Authentication authentication, @PathVariable String threadId) {
        return ResponseEntity.ok(travelPlannerAgentService.restore(currentUser(authentication), threadId));
    }

    @GetMapping("/plan/{threadId}/history")
    public ResponseEntity<?> history(Authentication authentication, @PathVariable String threadId) {
        return ResponseEntity.ok(travelPlannerAgentService.history(currentUser(authentication), threadId));
    }

    private boolean isPastDate(String value) {
        try {
            return java.time.LocalDate.parse(value.trim()).isBefore(java.time.LocalDate.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated user is required");
        }
        return authentication.getName();
    }

    private void requireThread(String threadId) {
        if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("Thread id is required");
    }

    private String safeError(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
