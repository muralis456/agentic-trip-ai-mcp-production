package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.graph.model.HotelExtraction;
import com.example.travel.model.HotelOption;
import com.example.travel.model.SearchHit;
import com.example.travel.service.RoutedLlm;
import com.example.travel.service.McpHotelSearchClient;
import com.example.travel.support.JsonSupport;
import com.example.travel.support.TripSlotHeuristics;
import com.example.travel.tool.HotelSearchTool;
import com.example.travel.tool.TavilySearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hotel agent.
 *
 * Two-step approach:
 *
 * 1. LLM + HotelSearchTool -> research
 * 2. LLM without tools -> JSON
 *
 * This avoids combining Groq tool calling with JSON generation
 * in the same request.
 */
@Service
public class HotelAgentService {

    private static final Logger log =
            LoggerFactory.getLogger(HotelAgentService.class);

    private final RoutedLlm routedLlm;
    private final HotelSearchTool hotelSearchTool;
    private final TavilySearchTool tavilySearchTool;
    private final JsonSupport jsonSupport;
    private final ObjectProvider<McpHotelSearchClient> mcpHotelSearchClient;

    public HotelAgentService(
            RoutedLlm routedLlm,
            HotelSearchTool hotelSearchTool,
            TavilySearchTool tavilySearchTool,
            JsonSupport jsonSupport,
            ObjectProvider<McpHotelSearchClient> mcpHotelSearchClient) {

        this.routedLlm = routedLlm;
        this.hotelSearchTool = hotelSearchTool;
        this.tavilySearchTool = tavilySearchTool;
        this.jsonSupport = jsonSupport;
        this.mcpHotelSearchClient = mcpHotelSearchClient;
    }

    public HotelSearchResult search(TravelState state) {

        String destinationCandidate = TravelState.firstNonBlank(
                TripSlotHeuristics.extractDestinationHint(state.userRequest()),
                state.destination());
        final String destination = TripSlotHeuristics.normalizePlace(destinationCandidate);
        boolean cheaper = state.hotelCheaper();

        log.info(
                "Hotel agent destination={} cheaper={}",
                destination,
                cheaper
        );

        if (TravelState.isBlank(destination)) {
            log.warn("Hotel search skipped because destination is missing from current request");
            return fallback("", cheaper);
        }

        // When the MCP hotel client is available, use its structured result directly.
        // Do not send the destination through a second LLM tool-selection round; that
        // round can lose the slot and return unrelated hotels.
        McpHotelSearchClient mcpClient = mcpHotelSearchClient.getIfAvailable();
        if (mcpClient != null && !TravelState.isBlank(destination)) {
            List<HotelOption> directHotels = mcpClient.search(
                    destination, state.travelStyle(), cheaper,
                    state.datesFlexible() ? null : state.departureDate(),
                    state.datesFlexible() ? null : state.returnDate(),
                    state.travelers(), 0, state.hotelBudget());
            List<HotelOption> providerHotels = directHotels;
            directHotels = providerHotels.stream()
                    .map(this::scrubPlaceholders)
                    .filter(hotel -> {
                        boolean valid = isValidHotelOption(hotel);
            if (!valid) {
                            log.warn("Hotel provider record rejected as non-property destination={} name={} area={}",
                                    destination, safe(hotel == null ? null : hotel.getName()), safe(hotel == null ? null : hotel.getArea()));
                        }
                        return valid;
                    })
                    .filter(hotel -> {
                        boolean relevant = isRelevantToDestination(hotel, destination);
            if (!relevant) {
                            log.warn("Hotel provider record rejected for destination mismatch destination={} name={} area={}",
                                    destination, safe(hotel.getName()), safe(hotel.getArea()));
                        }
                        return relevant;
                    })
                    .toList();
            if (!directHotels.isEmpty()) {
                log.info("Hotel agent using structured MCP results destination={} count={}",
                        destination, directHotels.size());
                return new HotelSearchResult(new ArrayList<>(directHotels), List.of(), false);
            }
            log.warn("Hotel provider returned no verified structured hotels destination={} — switching to independent travel-research fallback",
                    destination);
        }

        String user =
                "Destination=" + destination
                        + "\nCheaper=" + cheaper
                        + "\nHotel budget ceiling INR=" + (state.hotelBudget() == null ? "none" : state.hotelBudget())
                        + "\nStyle=" + state.travelStyle()
                        + "\nTravelers=" + state.travelers()
                        + "\nReplan notes=" + state.replanNotes();

        /*
         * =========================================================
         * STEP 1: HOTEL RESEARCH
         * =========================================================
         *
         * Tool is enabled.
         *
         * IMPORTANT:
         * Do not ask for JSON in this request.
         */
        String researchSystem =
                "You are the Hotel Research Agent. "
                        + "Find fresh hotel information for the requested destination. "
                        + "Use HotelSearchTool when fresh hotel data is required. "
                        + "Prefer 2-4 real, identifiable hotel properties. "
                        + "Never treat an article title, listicle, travel guide, neighborhood guide, search-result title, or generic phrase as a hotel name. "
                        + "Collect hotel name, area, price range, rating, "
                        + "family suitability and useful notes. "
                        + "After completing the search, return the findings as normal text. "
                        + "Do not return JSON.";

        String researchContent = "";

        try {
            // When the structured hotel provider returns an article/listicle or no
            // usable hotel records, use the independent travel-research channel.
            // This avoids displaying research titles as hotels while still recovering
            // genuine hotel properties when search results exist.
            String budget = state.hotelBudget() == null ? "" : " under INR " + state.hotelBudget().toPlainString();
            List<String> hotelQueries = List.of(
                    "actual hotel properties in " + destination + budget + " hotel name area nightly price rating official website",
                    "hotels in " + destination + budget + " property names guest ratings booking",
                    "site:booking.com hotels " + destination + budget
            );
            List<SearchHit> allResearchHits = new ArrayList<>();
            for (String query : hotelQueries) {
                try {
                    List<SearchHit> hits = tavilySearchTool.searchHits(query);
                    if (hits != null) allResearchHits.addAll(hits);
                } catch (Exception queryException) {
                    log.warn("Hotel research query failed destination={} query={}", destination, query, queryException);
                }
            }
            List<SearchHit> researchHits = allResearchHits.stream()
                    .filter(hit -> hit != null && !isResearchArticleTitle(hit.getTitle()))
                    .collect(java.util.stream.Collectors.toMap(
                            hit -> (safe(hit.getTitle()) + "|" + safe(hit.getUrl())).toLowerCase(Locale.ROOT),
                            hit -> hit,
                            (first, ignored) -> first,
                            java.util.LinkedHashMap::new))
                    .values().stream().limit(12).toList();
            if (!researchHits.isEmpty()) {
                researchContent = researchHits.stream()
                        .map(hit -> "TITLE: " + safe(hit.getTitle()) + "\nCONTENT: " + safe(hit.getContent()) + "\nURL: " + safe(hit.getUrl()))
                        .collect(java.util.stream.Collectors.joining("\n\n"));
                log.info("Hotel research fallback returned {} property-oriented hits from {} queries destination={}",
                        researchHits.size(), hotelQueries.size(), destination);
            } else {
                researchContent = routedLlm.complete(
                        AgentRole.EXTRACT,
                        researchSystem,
                        user,
                        hotelSearchTool
                );
            }

        } catch (Exception exception) {

            log.warn(
                    "Hotel research LLM failed for destination={}",
                    destination,
                    exception
            );
        }

        /*
         * If the research call failed, return the existing fallback.
         */
        if (researchContent == null || researchContent.isBlank()) {
            log.warn("Hotel search produced no research content destination={} cheaper={} budget={}",
                    destination, cheaper, state.hotelBudget());
            return fallback(destination, cheaper);
        }

        /*
         * =========================================================
         * STEP 2: STRUCTURE THE RESULT
         * =========================================================
         *
         * IMPORTANT:
         * No HotelSearchTool is supplied here.
         *
         * This is what prevents:
         *
         * "attempted to call tool 'json'"
         */
        String extractionSystem =
                "You are a Hotel Data Extraction Agent. "
                        + "Convert the hotel research into valid JSON only. "
                        + "Do not call any tools. "
                        + "Do not use markdown code fences. "
                        + "Use exactly this JSON structure: "
                        + "{\"hotels\":["
                        + "{\"name\":\"\","
                        + "\"area\":\"\","
                        + "\"priceRange\":\"\","
                        + "\"rating\":\"\","
                        + "\"suitableFor\":\"\","
                        + "\"notes\":\"\"}"
                        + "]}. "
                        + "Use only information present in the research. "
                        + "Do not invent hotels or prices. "
                        + "Prefer 2-4 real hotels. Never convert an article title or research heading into a hotel. "
                        + "If a field is unavailable, use an empty string. "
                        + "Never use placeholders such as "
                        + "'Not specified', 'N/A', 'unknown', or 'none'.";

        String extractionUser =
                "Destination=" + destination
                        + "\nCheaper=" + cheaper
                        + "\nHotel budget ceiling INR=" + (state.hotelBudget() == null ? "none" : state.hotelBudget())
                        + "\nStyle=" + state.travelStyle()
                        + "\nTravelers=" + state.travelers()
                        + "\nHotel budget ceiling INR=" + (state.hotelBudget() == null ? "none" : state.hotelBudget())
                        + "\n\nHotel research:\n"
                        + researchContent;

        String content = "";

        try {

            /*
             * No tool argument here.
             */
            content = routedLlm.complete(
                    AgentRole.EXTRACT,
                    extractionSystem,
                    extractionUser
            );

        } catch (Exception exception) {

            log.warn(
                    "Hotel extraction LLM failed for destination={}",
                    destination,
                    exception
            );
        }

        /*
         * =========================================================
         * STEP 3: EXISTING JSON SUPPORT
         * =========================================================
         */
        List<HotelOption> hotels =
                jsonSupport.read(
                        content,
                        HotelExtraction.class
                )
                .map(HotelExtraction::getHotels)
                .filter(list ->
                        list != null && !list.isEmpty()
                )
                .orElseGet(ArrayList::new)
                .stream()
                .map(this::scrubPlaceholders)
                .filter(this::isValidHotelOption)
                .filter(hotel -> isRelevantToDestination(hotel, destination))
                .toList();

        if (hotels.isEmpty()) {
            log.warn("Hotel extraction produced zero verified properties destination={} rawChars={} — no hotel card will be fabricated",
                    destination, content == null ? 0 : content.length());
            return fallback(destination, cheaper);
        }

        return new HotelSearchResult(
                new ArrayList<>(hotels),
                List.of(),
                false
        );
    }

    private boolean isResearchArticleTitle(String title) {
        if (title == null || title.isBlank()) return true;
        String lower = title.trim().toLowerCase(Locale.ROOT);
        return lower.contains("best value hotels")
                || lower.contains("best hotels in")
                || lower.contains("best tokyo")
                || lower.contains("top hotels")
                || lower.contains("hotels for a weekend getaway")
                || lower.contains("where to stay")
                || lower.contains("hotel guide")
                || lower.contains("travel guide")
                || lower.contains("list of hotels")
                || lower.matches(".*\\b(10|20|25|50|100)\\s+(best|top|hotels?|options?).*")
                || lower.matches(".*\\b20\\d{2}\\b.*");
    }

    private boolean isValidHotelOption(HotelOption hotel) {
        if (hotel == null || TravelState.isBlank(hotel.getName())) return false;
        String name = hotel.getName().trim();
        if (name.length() < 2 || name.length() > 120) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        // Research/article titles must never become hotel entities.
        if (lower.contains("best areas") || lower.contains("areas & hotels")
                || lower.contains("hotels to stay") || lower.contains("where to stay")
                || lower.contains("top hotels") || lower.contains("hotel guide")
                || lower.contains("accommodation guide") || lower.contains("travel guide")
                || lower.contains("things to do") || lower.contains("complete guide")
                || lower.matches(".*\\b(10|20|25|50|100)\\s+(best|top|hotels?|options?).*")) return false;
        if (lower.matches(".*\\b20\\d{2}\\b.*") || lower.matches(".*\\(\\s*\\d+\\s*(best|top|hotels?|options?)?.*")) return false;
        if (name.contains("http://") || name.contains("https://") || name.contains("|")) return false;
        if (name.split("\\s+").length > 11) return false;
        // A structured hotel should have at least a name and one supporting field.
        return !TravelState.isBlank(hotel.getArea())
                || !TravelState.isBlank(hotel.getPriceRange())
                || !TravelState.isBlank(hotel.getRating())
                || !TravelState.isBlank(hotel.getSuitableFor());
    }

    private boolean hasHotelName(HotelOption hotel) {
        return hotel != null && !TravelState.isBlank(hotel.getName());
    }

    private boolean isRelevantToDestination(HotelOption hotel, String destination) {
        String target = normalize(destination);
        if (target.isBlank()) {
            return true;
        }
        String haystack = normalize(String.join(" ",
                safe(hotel.getName()),
                safe(hotel.getArea()),
                safe(hotel.getNotes()),
                safe(hotel.getSuitableFor())));

        // Destination names that are common in the hotel metadata are sufficient.
        // For Dubai also accept the UAE label because hotel records often use it.
        if (target.equals("dubai")) {
            return haystack.contains("dubai") || haystack.contains("uae")
                    || haystack.contains("united arab emirates");
        }
        return haystack.contains(target);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private HotelOption scrubPlaceholders(HotelOption hotel) {

        hotel.setArea(
                clean(hotel.getArea())
        );

        hotel.setPriceRange(
                clean(hotel.getPriceRange())
        );

        hotel.setRating(
                clean(hotel.getRating())
        );

        hotel.setSuitableFor(
                clean(hotel.getSuitableFor())
        );

        hotel.setNotes(
                clean(hotel.getNotes())
        );

        if (isPlaceholder(hotel.getName())) {
            hotel.setName("");
        }

        return hotel;
    }

    private String clean(String value) {
        return isPlaceholder(value) ? "" : value;
    }

    private boolean isPlaceholder(String value) {

        if (value == null || value.isBlank()) {
            return true;
        }

        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        return normalized.equals("not specified")
                || normalized.equals("n/a")
                || normalized.equals("unknown")
                || normalized.equals("none");
    }

    private HotelSearchResult fallback(
            String destination,
            boolean cheaper) {

        // Never fabricate a hotel-shaped record when research/tool output is not structured.
        // The UI will render the empty state instead of turning an article title into a hotel.
        return new HotelSearchResult(new ArrayList<>(), List.of(), true);
    }

    public static final class HotelSearchResult {

        private final List<HotelOption> hotels;
        private final List<SearchHit> hits;
        private final boolean fallbackExhausted;

        public HotelSearchResult(
                List<HotelOption> hotels,
                List<SearchHit> hits,
                boolean fallbackExhausted) {

            this.hotels =
                    hotels == null
                            ? List.of()
                            : hotels;

            this.hits =
                    hits == null
                            ? List.of()
                            : hits;
            this.fallbackExhausted = fallbackExhausted;
        }

        public List<HotelOption> hotels() {
            return hotels;
        }

        public List<SearchHit> hits() {
            return hits;
        }

        public boolean fallbackExhausted() {
            return fallbackExhausted;
        }
    }
}