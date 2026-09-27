package com.example.travel.service;

import com.example.travel.dto.TravelPlanResponse;
import com.example.travel.dto.TripHistoryItem;
import com.example.travel.dto.TripPlanResult;
import com.example.travel.dto.TripHeader;
import com.example.travel.entity.ConversationMemory;
import com.example.travel.entity.TripHistory;
import com.example.travel.repository.ConversationMemoryRepository;
import com.example.travel.repository.TripHistoryRepository;
import com.example.travel.support.TripSlotHeuristics;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TripHistoryService {

    private final TripHistoryRepository repository;
    private final ConversationMemoryRepository conversationRepository;
    private final ObjectMapper objectMapper;

    public TripHistoryService(TripHistoryRepository repository,
                              ConversationMemoryRepository conversationRepository,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.conversationRepository = conversationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void saveOrUpdate(String userId, TravelPlanResponse response) {
        if (response == null || response.getThreadId() == null || response.getThreadId().isBlank()) return;
        if (!response.isTripPlanning()) return;
        TripPlanResult plan = response.getPlan();
        TripHeader trip = plan == null ? null : plan.getTrip();
        if (trip == null) return;

        // Never persist an incomplete/failed graph state as a "recent trip".
        // History retrieval must only return a genuine structured trip snapshot;
        // otherwise a failed itinerary can become the user's newest memory.
        if (trip.getDestination() == null || trip.getDestination().isBlank()) return;
        if (trip.getOrigin() == null || trip.getOrigin().isBlank()) return;
        if (plan.getItinerary() == null || plan.getItinerary().isEmpty()) {
            boolean hasDomainResult = (plan.getFlights() != null && !plan.getFlights().isEmpty())
                    || (plan.getHotels() != null && !plan.getHotels().isEmpty())
                    || (plan.getWeather() != null && (plan.getWeather().getSummary() != null && !plan.getWeather().getSummary().isBlank()))
                    || (plan.getBudget() != null && plan.getBudget().getLineItems() != null && !plan.getBudget().getLineItems().isEmpty());
            if (!hasDomainResult) return;
        }

        TripHistory entity = repository.findByUserIdAndThreadId(userId, response.getThreadId())
                .orElseGet(TripHistory::new);
        Instant now = Instant.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(response.getGeneratedAt() != null ? response.getGeneratedAt() : now);
        }
        // Keep the original creation time across approval/replan/modify cycles.
        response.setGeneratedAt(entity.getCreatedAt());
        response.setUpdatedAt(now);
        if (response.getPlan() != null && response.getPlan().getTrip() != null) {
            response.getPlan().getTrip().setGeneratedAt(entity.getCreatedAt());
            response.getPlan().getTrip().setUpdatedAt(now);
        }
        entity.setUserId(userId);
        entity.setThreadId(response.getThreadId());
        entity.setTitle(firstNonBlank(trip.getTitle(), buildTitle(trip)));
        entity.setOrigin(trip.getOrigin());
        entity.setDestination(trip.getDestination());
        entity.setDepartureDate(trip.getDepartureDate());
        entity.setReturnDate(trip.getReturnDate());
        entity.setTravelers(trip.getTravelers());
        entity.setBudgetLabel(trip.getBudgetLabel());
        entity.setStatus(firstNonBlank(response.getStatus(), trip.getStatus(), "COMPLETE"));
        entity.setAwaitingApproval(response.isAwaitingApproval() || trip.isAwaitingApproval());
        entity.setQualityScore(trip.getQualityScore());
        entity.setUpdatedAt(now);
        try {
            entity.setPlanJson(objectMapper.writeValueAsString(response));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save trip history", ex);
        }
        repository.save(entity);
    }

    /**
     * Returns structured trips first and also exposes older trip-like conversations
     * that were written before trip_history existed. Legacy rows are read-only
     * views over conversation_memory; they are not fabricated structured plans.
     */
    @Transactional(readOnly = true)
    public List<TripHistoryItem> list(String userId, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        List<TripHistoryItem> result = new ArrayList<>();
        Set<String> knownThreads = new HashSet<>();

        repository.findByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, size))
                .forEach(t -> {
                    result.add(toItem(t));
                    knownThreads.add(t.getThreadId());
                });

        // Read enough historical conversation rows to recover older trips that
        // predate trip_history. We deliberately do not treat weather/hotel-only
        // questions as trips.
        int legacyScan = Math.min(Math.max(size * 8, 100), 500);
        List<ConversationMemory> memories = conversationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, legacyScan));

        for (ConversationMemory memory : memories) {
            if (!"user".equalsIgnoreCase(memory.getRole())) continue;
            String sessionId = firstNonBlank(memory.getSessionId());
            if (sessionId.isBlank()) continue;
            String text = firstNonBlank(memory.getContent());
            if (!isLegacyTripRequest(text)) continue;

            String origin = TripSlotHeuristics.extractOriginHint(text);
            String destination = TripSlotHeuristics.extractDestinationHint(text);
            String route = !origin.isBlank() && !destination.isBlank()
                    ? origin + " → " + destination
                    : destination.isBlank() ? "Previous trip" : destination + " Trip";

            // Negative IDs cannot collide with real trip_history IDs. The UI uses
            // legacy=true and opens the original conversation rather than treating
            // this lightweight record as a structured trip plan.
            long syntheticId = -Math.max(1L, memory.getId() == null ? Math.abs((long) sessionId.hashCode()) : memory.getId());
            result.add(new TripHistoryItem(
                    syntheticId,
                    sessionId,
                    route,
                    origin,
                    destination,
                    "",
                    "",
                    1,
                    "",
                    "LEGACY",
                    false,
                    0,
                    memory.getCreatedAt(),
                    memory.getCreatedAt(),
                    true));
            knownThreads.add(sessionId);
            if (result.size() >= size) break;
        }

        result.sort(Comparator.comparing(TripHistoryItem::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result.size() > size ? List.copyOf(result.subList(0, size)) : List.copyOf(result);
    }

    /**
     * Removes a saved trip and its conversation-memory rows. The caller must
     * supply an ID belonging to the requested user.
     */
    @Transactional
    public boolean deleteTripAndMemory(String userId, Long id) {
        if (userId == null || userId.isBlank() || id == null || id <= 0) return false;
        TripHistory entity = repository.findByUserIdAndId(userId, id).orElse(null);
        if (entity == null) return false;
        String threadId = entity.getThreadId();
        repository.delete(entity);
        if (threadId != null && !threadId.isBlank()) {
            conversationRepository.deleteByUserIdAndSessionId(userId, threadId);
        }
        return true;
    }

    /**
     * Retrieves a saved trip using a semantic selection policy decided by the
     * Intent Agent. The important distinction is that "my recent trip" means
     * the user's recent finalized trip, not the most recently written
     * conversation row or a pending draft.
     */
    @Transactional(readOnly = true)
    public String findPlanJson(String userId, String selection) {
        if (userId == null || userId.isBlank()) return "";
        String policy = normalizeSelection(selection);

        List<TripHistory> recentTrips = repository.findByUserIdOrderByUpdatedAtDesc(
                userId, PageRequest.of(0, 50));

        // First search the structured trip store. Status is evaluated before
        // recency so an unrelated/pending draft cannot shadow the latest
        // finalized trip merely because it was edited more recently.
        for (TripHistory trip : recentTrips) {
            if (!matchesSelection(trip, policy)) continue;
            if (isValidTripPlanJson(trip.getPlanJson())) return trip.getPlanJson();
        }

        // A specific/approved lookup should not silently downgrade to an
        // unrelated conversation. RECENT_ANY is the only policy allowed to
        // fall back to the newest valid saved trip when status filtering finds
        // nothing.
        if ("RECENT_ANY".equals(policy)) {
            for (TripHistory trip : recentTrips) {
                if (isValidTripPlanJson(trip.getPlanJson())) return trip.getPlanJson();
            }
        }

        // Backward-compatible fallback for trips stored only in conversation_memory.
        // We still require a genuine structured trip response; ordinary assistant
        // messages such as travel tips are never treated as a saved trip.
        if ("CONVERSATION".equals(policy) || "RECENT_ANY".equals(policy) || "APPROVED_RECENT".equals(policy)) {
            List<ConversationMemory> memories = conversationRepository
                    .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100));
            for (ConversationMemory memory : memories) {
                if (!"assistant".equalsIgnoreCase(memory.getRole())) continue;
                String structured = memory.getStructuredData();
                if (structured == null || structured.isBlank()) continue;
                if (isValidTripPlanJson(structured)) {
                    TravelPlanResponse candidate = readPlan(structured);
                    if (candidate == null) continue;
                    if ("APPROVED_RECENT".equals(policy) &&
                            (candidate.isAwaitingApproval() || "PENDING_APPROVAL".equalsIgnoreCase(candidate.getStatus()))) {
                        continue;
                    }
                    return structured;
                }
            }
        }
        return "";
    }

    /** Backward-compatible default: recent finalized trip. */
    @Transactional(readOnly = true)
    public String findMostRecentPlanJson(String userId) {
        return findPlanJson(userId, "APPROVED_RECENT");
    }

    private boolean matchesSelection(TripHistory trip, String policy) {
        if (trip == null || !isValidTripPlanJson(trip.getPlanJson())) return false;
        String status = firstNonBlank(trip.getStatus(), "").toUpperCase();
        boolean awaiting = trip.isAwaitingApproval();
        return switch (policy) {
            case "PENDING" -> awaiting || "PENDING_APPROVAL".equals(status);
            case "REJECTED" -> "REJECTED".equals(status);
            case "APPROVED_RECENT" -> !awaiting && ("COMPLETE".equals(status) || "APPROVED".equals(status) || "CONFIRMED".equals(status));
            case "SPECIFIC", "RECENT_ANY", "CONVERSATION" -> true;
            default -> !awaiting && ("COMPLETE".equals(status) || "APPROVED".equals(status) || "CONFIRMED".equals(status));
        };
    }

    private String normalizeSelection(String selection) {
        if (selection == null || selection.isBlank()) return "APPROVED_RECENT";
        String normalized = selection.trim().toUpperCase();
        return Set.of("APPROVED_RECENT", "RECENT_ANY", "PENDING", "REJECTED", "SPECIFIC", "CONVERSATION")
                .contains(normalized) ? normalized : "APPROVED_RECENT";
    }

    private TravelPlanResponse readPlan(String json) {
        try {
            return objectMapper.readValue(json, TravelPlanResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isValidTripPlanJson(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            TravelPlanResponse response = objectMapper.readValue(json, TravelPlanResponse.class);
            if (response == null || !response.isTripPlanning() || response.getPlan() == null) return false;
            TripHeader trip = response.getPlan().getTrip();
            if (trip == null || trip.getOrigin() == null || trip.getOrigin().isBlank()
                    || trip.getDestination() == null || trip.getDestination().isBlank()) return false;
            if (response.getPlan().getItinerary() != null && !response.getPlan().getItinerary().isEmpty()) return true;
            return (response.getPlan().getFlights() != null && !response.getPlan().getFlights().isEmpty())
                    || (response.getPlan().getHotels() != null && !response.getPlan().getHotels().isEmpty())
                    || (response.getPlan().getWeather() != null
                        && response.getPlan().getWeather().getSummary() != null
                        && !response.getPlan().getWeather().getSummary().isBlank())
                    || (response.getPlan().getBudget() != null
                        && response.getPlan().getBudget().getLineItems() != null
                        && !response.getPlan().getBudget().getLineItems().isEmpty());
        } catch (Exception ignored) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public TravelPlanResponse getPlan(String userId, Long id) {
        if (id != null && id < 0) {
            long sourceMessageId = Math.abs(id);
            ConversationMemory memory = conversationRepository.findById(sourceMessageId)
                    .orElseThrow(() -> new IllegalArgumentException("Previous trip not found"));
            if (!userId.equals(memory.getUserId())) throw new IllegalArgumentException("Previous trip not found");
            throw new IllegalArgumentException("This is a previous conversation. Open it from My Trips.");
        }
        TripHistory entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found"));
        try {
            return objectMapper.readValue(entity.getPlanJson(), TravelPlanResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not load saved trip", ex);
        }
    }

    private TripHistoryItem toItem(TripHistory t) {
        return new TripHistoryItem(t.getId(), t.getThreadId(), t.getTitle(), t.getOrigin(),
                t.getDestination(), t.getDepartureDate(), t.getReturnDate(), t.getTravelers(),
                t.getBudgetLabel(), t.getStatus(), t.isAwaitingApproval(), t.getQualityScore(),
                t.getCreatedAt(), t.getUpdatedAt(), false);
    }

    private boolean isLegacyTripRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        String origin = TripSlotHeuristics.extractOriginHint(text);
        String destination = TripSlotHeuristics.extractDestinationHint(text);
        boolean tripWords = lower.contains("trip") || lower.contains("travel") || lower.contains("holiday")
                || lower.contains("vacation") || lower.contains("journey") || lower.contains("itinerary");
        boolean planningWords = lower.contains("plan") || lower.contains("planning") || lower.contains("itinerary")
                || lower.contains("day by day") || lower.contains("day-by-day");
        boolean specialistOnly = lower.contains("weather") || lower.contains("forecast")
                || lower.contains("hotel") || lower.contains("hotels") || lower.contains("flight")
                || lower.contains("flights") || lower.contains("budget") || lower.contains("cost");
        if (specialistOnly && !planningWords) return false;
        return tripWords && (planningWords || !specialistOnly) && (!destination.isBlank() || planningWords);
    }

    private String buildTitle(TripHeader trip) {
        String destination = firstNonBlank(trip.getDestination(), "Trip");
        return destination + " Trip";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
}
