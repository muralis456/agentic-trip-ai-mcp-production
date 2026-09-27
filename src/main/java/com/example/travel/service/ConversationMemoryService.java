package com.example.travel.service;

import com.example.travel.entity.ConversationMemory;
import com.example.travel.repository.ConversationMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final int MAX_HISTORY = 6;
    private static final int MAX_CONTENT = 400;
    private static final int UI_HISTORY_LIMIT = 50;
    private static final int UI_CONTENT_MAX = 12000;

    private final ConversationMemoryRepository repository;
    private final ObjectMapper objectMapper;

    public ConversationMemoryService(ConversationMemoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Commits immediately (REQUIRES_NEW) so a search is stored even if the long
     * travel graph later fails or the HTTP client times out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveMessage(String userId, String sessionId, String role, String content) {
        saveMessage(userId, sessionId, sessionId, role, content);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveMessage(String userId, String sessionId, String conversationId, String role, String content) {
        ConversationMemory memory = new ConversationMemory();
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setConversationId(conversationId);
        memory.setRole(role);
        memory.setContent(truncate(content, MAX_CONTENT));
        memory.setCreatedAt(Instant.now());
        ConversationMemory saved = repository.saveAndFlush(memory);
        log.info("Saved conversation memory id={} userId={} role={} chars={}",
                saved.getId(), userId, role, memory.getContent().length());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveUiMessage(String userId, String sessionId, String role, String content) {
        saveUiMessage(userId, sessionId, role, content, null);
    }

    /**
     * Saves a UI message and, when supplied, the exact structured response so
     * Recent Trips can restore the same result after a browser refresh.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveUiMessage(String userId, String sessionId, String role, String content, Object structuredPayload) {
        saveUiMessage(userId, sessionId, sessionId, role, content, structuredPayload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveUiMessage(String userId, String sessionId, String conversationId, String role, String content, Object structuredPayload) {
        ConversationMemory memory = new ConversationMemory();
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setConversationId(conversationId);
        memory.setRole(role);
        memory.setContent(truncate(content, UI_CONTENT_MAX));
        memory.setCreatedAt(Instant.now());
        if (structuredPayload != null) {
            try {
                memory.setStructuredData(objectMapper.writeValueAsString(structuredPayload));
            } catch (Exception ex) {
                log.warn("Could not serialize structured UI payload userId={} sessionId={}", userId, sessionId, ex);
            }
        }
        ConversationMemory saved = repository.saveAndFlush(memory);
        log.info("Saved UI conversation memory id={} userId={} role={} structured={} chars={}",
                saved.getId(), userId, role, memory.getStructuredData() != null, memory.getContent().length());
    }

    /**
     * Deletes all persisted conversation-memory rows for one owned session.
     * Recent History uses sessionId/threadId as the conversation boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long deleteSession(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        long deleted = repository.deleteByUserIdAndSessionId(userId, sessionId);
        log.info("Deleted conversation memory rows={} userId={} sessionId={}", deleted, userId, sessionId);
        return deleted;
    }

    @Transactional(readOnly = true)
    public long countForUser(String userId) {
        return repository.countByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> getHistoryForUi(String userId, int limit) {
        int size = Math.min(Math.max(limit, 1), UI_HISTORY_LIMIT);
        PageRequest pageable = PageRequest.of(0, size);
        List<ConversationMemory> memories = repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        log.info("Loaded {} history row(s) for userId={}", memories.size(), userId);
        return memories.stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(m -> new HistoryItem(
                        m.getId(),
                        m.getSessionId(),
                        m.getConversationId(),
                        m.getRole(),
                        m.getContent(),
                        m.getStructuredData(),
                        m.getCreatedAt() == null ? null : m.getCreatedAt().toString()))
                .collect(Collectors.toList());
    }

    public record HistoryItem(Long id, String sessionId, String conversationId, String role, String content, String structuredData, String createdAt) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ConversationMemory> getMessageForUi(String userId, Long id) {
        return repository.findById(id)
                .filter(message -> userId.equals(message.getUserId()));
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> getHistoryForSessionUi(String userId, String sessionId, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        PageRequest pageable = PageRequest.of(0, size);
        return repository.findByUserIdAndSessionIdOrderByCreatedAtDesc(userId, sessionId, pageable)
                .stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(m -> new HistoryItem(
                        m.getId(),
                        m.getSessionId(),
                        m.getConversationId(),
                        m.getRole(),
                        m.getContent(),
                        m.getStructuredData(),
                        m.getCreatedAt() == null ? null : m.getCreatedAt().toString()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<HistoryItem> getHistoryForConversationUi(String userId, String conversationId, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        PageRequest pageable = PageRequest.of(0, size);
        return repository.findByUserIdAndConversationIdOrderByCreatedAtDesc(userId, conversationId, pageable)
                .stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(m -> new HistoryItem(
                        m.getId(),
                        m.getSessionId(),
                        m.getConversationId(),
                        m.getRole(),
                        m.getContent(),
                        m.getStructuredData(),
                        m.getCreatedAt() == null ? null : m.getCreatedAt().toString()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public String buildHistoryContext(String userId, String sessionId) {
        List<ConversationMemory> history = loadRecentHistory(userId, sessionId);
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Short-term conversation (last turns only):\n");
        for (ConversationMemory memory : history) {
            sb.append(memory.getRole()).append(": ").append(truncate(memory.getContent(), MAX_CONTENT)).append("\n");
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public List<ConversationMemory> getRecentHistory(String userId, String sessionId) {
        return loadRecentHistory(userId, sessionId);
    }

    /**
     * Loads memory across all graph threads that belong to one stable conversation.
     * This is the backend source of truth for follow-up turns.
     */
    /**
     * Hydrates missing request slots from the latest structured assistant result.
     * This makes conversation memory a backend capability rather than a browser-only
     * convenience: API clients can ask "what is the weather?" after a destination
     * was established in an earlier turn without resending the destination.
     */
    @Transactional(readOnly = true)
    public void hydrateRequestFromConversation(String userId, String conversationId, com.example.travel.dto.TravelRequest request) {
        if (request == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        // Capture explicit route entities from the CURRENT turn before loading memory.
        // Memory is context, never an override for information the user just supplied.
        String currentOriginHint = com.example.travel.support.TripSlotHeuristics.extractOriginHint(request.getPrompt());
        String currentDestinationHint = com.example.travel.support.TripSlotHeuristics.extractDestinationHint(request.getPrompt());

        List<ConversationMemory> history = loadRecentConversationHistory(userId, conversationId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMemory memory = history.get(i);
            if (!"assistant".equalsIgnoreCase(memory.getRole())
                    || memory.getStructuredData() == null
                    || memory.getStructuredData().isBlank()) {
                continue;
            }
            try {
                com.example.travel.dto.TravelPlanResponse response =
                        objectMapper.readValue(memory.getStructuredData(), com.example.travel.dto.TravelPlanResponse.class);
                if (response.getPlan() == null || response.getPlan().getTrip() == null) {
                    continue;
                }
                com.example.travel.dto.TripHeader trip = response.getPlan().getTrip();
                if (isBlank(request.getDestination())) request.setDestination(trip.getDestination());
                if (isBlank(request.getDepartureCity())) request.setDepartureCity(trip.getOrigin());

                // Dates are special: current-turn date language must win, and a
                // stale date from a previous trip must never be sent to providers.
                // Only inherit a historical date when the current turn contains
                // no date hint and the historical departure is today or future.
                boolean currentTurnHasDateHint =
                        com.example.travel.support.TripSlotHeuristics.hasDateHint(request.getPrompt())
                        || com.example.travel.support.TripSlotHeuristics.hasDurationHint(request.getPrompt());
                java.time.LocalDate today = java.time.LocalDate.now();
                java.time.LocalDate historicalDeparture = parseDate(trip.getDepartureDate());
                if (isBlank(request.getDepartureDate()) && !currentTurnHasDateHint
                        && historicalDeparture != null && !historicalDeparture.isBefore(today)) {
                    request.setDepartureDate(trip.getDepartureDate());
                }
                java.time.LocalDate historicalReturn = parseDate(trip.getReturnDate());
                if (isBlank(request.getReturnDate()) && !currentTurnHasDateHint
                        && historicalReturn != null && !historicalReturn.isBefore(today)) {
                    request.setReturnDate(trip.getReturnDate());
                }
                if (isBlank(request.getBudget())) request.setBudget(trip.getBudgetLabel());
                if (isBlank(request.getTravelStyle())) request.setTravelStyle(trip.getTravelStyle());

                // Explicit current-turn route values always win over hydrated memory.
                if (!isBlank(currentOriginHint)) request.setDepartureCity(currentOriginHint);
                if (!isBlank(currentDestinationHint)) request.setDestination(currentDestinationHint);
                return;
            } catch (Exception ex) {
                log.debug("Could not hydrate request from conversation memory conversationId={}", conversationId, ex);
            }
        }
    }

    private java.time.LocalDate parseDate(String value) {
        if (isBlank(value)) return null;
        try { return java.time.LocalDate.parse(value.trim()); }
        catch (Exception ignored) { return null; }
    }

    @Transactional(readOnly = true)
    public String buildConversationHistoryContext(String userId, String conversationId) {
        List<ConversationMemory> history = loadRecentConversationHistory(userId, conversationId);
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Conversation memory (previous turns in this conversation):\n");
        for (ConversationMemory memory : history) {
            sb.append(memory.getRole()).append(": ")
                    .append(truncate(memory.getContent(), MAX_CONTENT))
                    .append("\n");
            // Assistant rows contain the exact structured specialist result.
            // Keep a bounded copy in agent context so follow-ups can remember
            // destination, weather, RAG answer, hotels, flights, etc. without
            // requiring the browser to hydrate request fields.
            if ("assistant".equalsIgnoreCase(memory.getRole())
                    && memory.getStructuredData() != null
                    && !memory.getStructuredData().isBlank()) {
                sb.append("assistant_context: ")
                        .append(truncate(memory.getStructuredData(), 3500))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public List<ConversationMemory> getRecentConversationHistory(String userId, String conversationId) {
        return loadRecentConversationHistory(userId, conversationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long deleteConversation(String userId, String conversationId) {
        if (userId == null || userId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        return repository.deleteByUserIdAndConversationId(userId, conversationId);
    }

    @Transactional(readOnly = true)
    public List<ConversationMemory> getRecentHistory(String userId) {
        PageRequest pageable = PageRequest.of(0, MAX_HISTORY);
        List<ConversationMemory> memories = repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return List.copyOf(memories.stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .collect(Collectors.toList()));
    }

    private List<ConversationMemory> loadRecentConversationHistory(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        PageRequest pageable = PageRequest.of(0, MAX_HISTORY * 3);
        return repository.findByUserIdAndConversationIdOrderByCreatedAtDesc(userId, conversationId, pageable).stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .collect(Collectors.toList());
    }

    private List<ConversationMemory> loadRecentHistory(String userId, String sessionId) {
        PageRequest pageable = PageRequest.of(0, MAX_HISTORY);
        List<ConversationMemory> memories = repository.findByUserIdAndSessionIdOrderByCreatedAtDesc(userId, sessionId, pageable);
        return memories.stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .collect(Collectors.toList());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String content, int max) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "...";
    }
}
