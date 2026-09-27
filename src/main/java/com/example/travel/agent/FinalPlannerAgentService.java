package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.model.BudgetLineItem;
import com.example.travel.model.BudgetSummary;
import com.example.travel.model.FlightOption;
import com.example.travel.model.HotelOption;
import com.example.travel.model.ItineraryDay;
import com.example.travel.service.RoutedLlm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FinalPlannerAgentService {

    private static final Logger log = LoggerFactory.getLogger(FinalPlannerAgentService.class);

    private final RoutedLlm routedLlm;

    public FinalPlannerAgentService(RoutedLlm routedLlm) {
        this.routedLlm = routedLlm;
    }

    /**
     * Final Agent after Validator: facts are rendered from TravelState (no hallucination),
     * then a short Tips section is optionally polished by the LLM.
     */
    public String buildTips(TravelState state) {
        return polishTips(state);
    }

    /**
     * @deprecated Use {@link TripPlanAssembler} + {@link com.example.travel.dto.TripPlanResult} for API responses.
     */
    @Deprecated
    public String compose(TravelState state) {
        log.info("Final agent composing validated report for destination={}", state.destination());
        String factual = buildFactualReport(state);
        String tips = polishTips(state);
        return factual + "\n\n**Tips**\n" + tips;
    }

    private String buildFactualReport(TravelState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Final Trip Report: ").append(state.origin()).append(" to ")
                .append(state.destination()).append("**\n\n");

        sb.append("**Overview**\n");
        sb.append(state.origin()).append(" (").append(state.originIata()).append(") → ")
                .append(state.destination()).append(" (").append(state.destinationIata()).append(")\n");
        sb.append("Dates: ").append(state.departureDate()).append(" → ").append(state.returnDate())
                .append(" (").append(state.nights()).append(" nights)\n");
        sb.append("Travelers: ").append(state.travelers())
                .append(" · Style: ").append(state.travelStyle()).append('\n');
        if (!TravelState.isBlank(state.userRequest())) {
            sb.append("Request: ").append(state.userRequest()).append('\n');
        }

        sb.append("\n**Flights**\n");
        if (!state.includeFlightsInReport()) {
            sb.append("Not requested for this query.\n");
        } else if (!state.hasUsableFlights()) {
            sb.append("No reliable flight options were returned for this route/date.\n");
        } else {
            for (FlightOption flight : state.flights()) {
                if ("unavailable".equalsIgnoreCase(nullToEmpty(flight.getStatus()))) {
                    continue;
                }
                sb.append("- ").append(flight.toDisplay()).append('\n');
            }
        }

        sb.append("\n**Hotels**\n");
        if (!state.includeHotelsInReport()) {
            sb.append("Not requested for this query.\n");
        } else if (!state.hasHotelResults()) {
            sb.append("No hotel options extracted.\n");
        } else {
            for (HotelOption hotel : state.hotels()) {
                sb.append("- ").append(hotel.toDisplay()).append('\n');
            }
        }

        sb.append("\n**Day-by-day itinerary**\n");
        if (!state.includeItineraryInReport()) {
            sb.append("Not requested for this query.\n");
        } else if (state.itinerary() != null && state.itinerary().getDays() != null) {
            for (ItineraryDay day : state.itinerary().getDays()) {
                sb.append("Day ").append(day.getDay());
                if (!TravelState.isBlank(day.getTitle())) {
                    sb.append(" — ").append(day.getTitle());
                }
                sb.append('\n');
                String activities = day.activitiesText();
                if (!TravelState.isBlank(activities)) {
                    sb.append(activities).append('\n');
                }
            }
        }

        sb.append("\n**Budget**\n");
        if (!state.includeBudgetInReport()) {
            sb.append("Not requested for this query.\n");
        } else {
            BudgetSummary budget = state.budgetSummary();
            if (budget != null) {
                for (BudgetLineItem item : budget.getLineItems()) {
                    sb.append("- ").append(item.getCategory()).append(": ₹").append(item.getAmountInr()).append('\n');
                }
                if (budget.getEstimatedCost() != null) {
                    sb.append("Total estimated: ₹").append(budget.getEstimatedCost()).append('\n');
                }
                if (budget.getRemaining() != null) {
                    sb.append("Remaining vs ceiling: ₹").append(budget.getRemaining()).append('\n');
                }
                if (!TravelState.isBlank(budget.getAssessment())) {
                    sb.append(budget.getAssessment()).append('\n');
                }
            } else {
                sb.append(state.budgetLabel()).append('\n');
            }
        }

        if (state.includeWeatherInReport() && state.weather() != null) {
            sb.append("\n**Weather**\n").append(state.weather().toDisplay()).append('\n');
        }
        if (!state.ragContext().isBlank()) {
            sb.append("\n**Knowledge used by Agentic RAG**\n");
            if (!state.ragSources().isEmpty()) {
                sb.append("Sources: ").append(String.join(", ", state.ragSources())).append('\n');
            }
            sb.append(state.ragContext()).append('\n');
        }
        if (!state.semanticNotes().isEmpty()) {
            sb.append("\n**Semantic review**\n");
            for (String note : state.semanticNotes()) {
                sb.append("- ").append(note).append('\n');
            }
        }
        if (!state.provenance().isEmpty()) {
            sb.append("\n**Sources**\n");
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (var event : state.provenance()) {
                String line = event.toDisplay();
                if (seen.add(line)) {
                    sb.append("- ").append(line).append('\n');
                }
            }
        }
        return sb.toString().trim();
    }

    private String polishTips(TravelState state) {
        try {
            String tips = routedLlm.complete(AgentRole.FINAL,
                    "Write 3-5 short practical travel tips only. Do not mention flights, hotels, or prices. "
                            + "Do not invent attractions not listed. No JSON.",
                    """
                            Destination: %s
                            Style: %s
                            Weather: %s
                            Attractions: %s
                            Research: %s
                            Knowledge context: %s
                            Knowledge sources: %s
                            """.formatted(
                            state.destination(),
                            state.travelStyle(),
                            state.weather() == null ? "" : state.weather().toDisplay(),
                            state.attractions().stream().map(a -> a.toDisplay()).toList(),
                            state.research().stream().map(r -> r.toDisplay()).limit(4).toList(),
                            state.ragContext(),
                            state.ragSources()));
            if (isSafeTips(tips)) {
                return tips.trim();
            }
            if (tips != null && !tips.isBlank()) {
                log.warn("Final tips response rejected because it contains an internal/error or oversized response");
            }
        } catch (Exception exception) {
            if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Final tips LLM failed; using default tips", exception);
        }
        return defaultTips(state);
    }

    private boolean isSafeTips(String tips) {
        if (tips == null || tips.isBlank() || tips.length() > 1200) return false;
        String lower = tips.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("llm budget exhausted")
                && !lower.contains("budget exhausted")
                && !lower.contains("rate limit")
                && !lower.contains("quota exceeded")
                && !lower.contains("model unavailable")
                && !lower.contains("stack trace");
    }

    private String defaultTips(TravelState state) {
        StringBuilder sb = new StringBuilder();
        if (state.weather() != null && state.weather().isRainLikely()) {
            sb.append("- Pack a light rain jacket; keep indoor museum/cafe backups for wet hours.\n");
        }
        sb.append("- Keep some cash/card ready for transit and casual meals.\n");
        if (state.needsHotels()) {
            sb.append("- Confirm the hotel's check-in time and arrival instructions before travel.\n");
        } else {
            sb.append("- Keep a small time and cash buffer for local transport and unexpected delays.\n");
        }
        return sb.toString().trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
