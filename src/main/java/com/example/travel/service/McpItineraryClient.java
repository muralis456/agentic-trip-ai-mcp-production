package com.example.travel.service;

import com.example.travel.model.Itinerary;
import com.example.travel.model.ItineraryActivity;
import com.example.travel.model.ItineraryDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpItineraryClient {

    private static final Logger log = LoggerFactory.getLogger(McpItineraryClient.class);
    private final McpToolClient client;

    public McpItineraryClient(McpToolClient client) {
        this.client = client;
    }

    public Itinerary generate(String destination, LocalDate startDate, LocalDate endDate,
                              int adults, String travelStyle, boolean foodExperience,
                              boolean localExperience, boolean familyFriendly, String budgetLabel) {
        try {
            int days = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate));
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("destination", destination == null ? "" : destination);
            arguments.put("days", Math.min(14, days));
            arguments.put("startDate", startDate == null ? null : startDate.toString());
            arguments.put("endDate", endDate == null ? null : endDate.toString());
            arguments.put("adults", Math.max(1, adults));
            arguments.put("travelStyle", travelStyle == null ? "balanced" : travelStyle);
            arguments.put("foodExperience", foodExperience);
            arguments.put("localExperience", localExperience);
            arguments.put("familyFriendly", familyFriendly);
            arguments.put("budgetLabel", budgetLabel == null ? "medium" : budgetLabel);

            JsonNode root = client.invokePreferred(
                    "generate_itinerary",
                    "Generate a complete day-by-day itinerary",
                    "Generate a real named-venue itinerary for " + destination
                            + " from " + startDate + " to " + endDate
                            + ". Include food and local experiences where requested.",
                    arguments);

            if (root == null || !root.path("success").asBoolean(false)) {
                String message = root == null ? "Empty MCP itinerary response" : root.path("message").asString("Jettova itinerary failed");
                throw new IllegalStateException(message);
            }

            Itinerary itinerary = map(root);
            if (itinerary.isEmpty()) {
                throw new IllegalStateException("Jettova returned an empty itinerary");
            }
            itinerary.setProvider(root.path("provider").asString("Jettova"));
            return itinerary;
        } catch (Exception exception) {
            log.warn("mcp.itinerary.provider-failure destination={} errorType={} reason={}",
                    destination, exception.getClass().getSimpleName(), abbreviate(exception.getMessage()));
            throw new IllegalStateException("Jettova itinerary unavailable: " + abbreviate(exception.getMessage()), exception);
        }
    }

    private Itinerary map(JsonNode root) {
        Itinerary itinerary = new Itinerary();
        itinerary.setSummary(root.path("summary").asString(""));
        List<ItineraryDay> days = new ArrayList<>();
        JsonNode dayNodes = root.path("days");
        if (!dayNodes.isArray()) return itinerary;

        for (JsonNode dayNode : dayNodes) {
            ItineraryDay day = new ItineraryDay();
            day.setDay(dayNode.path("day").asInt(days.size() + 1));
            day.setTitle(dayNode.path("title").asString("Explore"));
            day.setSummary(dayNode.path("summary").asString(""));
            day.setEstimatedCost(dayNode.path("estimatedCost").asString(""));
            day.setCurrency(dayNode.path("currency").asString(""));
            List<ItineraryActivity> activities = new ArrayList<>();
            JsonNode activityNodes = dayNode.path("activities");
            if (activityNodes.isArray()) {
                for (JsonNode node : activityNodes) {
                    String name = node.path("name").asString("");
                    if (name.isBlank()) continue;
                    ItineraryActivity activity = new ItineraryActivity();
                    activity.setName(name);
                    activity.setType(node.path("type").asString("sightseeing"));
                    activity.setDescription(node.path("description").asString(""));
                    activity.setLocation(node.path("location").asString(""));
                    activity.setDuration(node.path("duration").asString(""));
                    activity.setEstimatedCost(node.path("estimatedCost").asString(""));
                    activity.setCurrency(node.path("currency").asString(""));
                    activity.setBookingUrl(node.path("bookingUrl").asString(""));
                    activity.setImageUrl(node.path("imageUrl").asString(""));
                    activity.setIndoorOutdoor(node.path("indoorOutdoor").asString("mixed"));
                    activity.setFamilyFriendly(node.path("familyFriendly").asBoolean(false));
                    activity.setFoodExperience(node.path("foodExperience").asBoolean(false));
                    activity.setLocalExperience(node.path("localExperience").asBoolean(false));
                    activities.add(activity);
                }
            }
            day.setActivities(activities);
            days.add(day);
        }
        itinerary.setDays(days);
        return itinerary;
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.replace('\n', ' ').trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }
}
