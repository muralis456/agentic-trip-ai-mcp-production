package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ItineraryDay implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int day;
    private String title;
    private List<ItineraryActivity> activities = new ArrayList<>();
    private String summary = "";
    private String estimatedCost = "";
    private String currency = "";

    public ItineraryDay() {
    }

    public ItineraryDay(int day, String title, List<ItineraryActivity> activities) {
        this.day = day;
        this.title = title;
        this.activities = activities == null ? new ArrayList<>() : activities;
    }

    public int getDay() {
        return day;
    }

    public void setDay(int day) {
        this.day = day;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<ItineraryActivity> getActivities() {
        return activities;
    }

    public void setActivities(List<ItineraryActivity> activities) {
        this.activities = activities == null ? new ArrayList<>() : activities;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary == null ? "" : summary; }
    public String getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(String estimatedCost) { this.estimatedCost = estimatedCost == null ? "" : estimatedCost; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency == null ? "" : currency; }

    public String activitiesText() {
        if (activities == null || activities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ItineraryActivity activity : activities) {
            if (activity == null || activity.getName() == null || activity.getName().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("• ").append(activity.getName());
            if (!activity.getType().isBlank()) {
                sb.append(" — ").append(activity.getType());
            }
            List<String> tags = new ArrayList<>();
            if (!"mixed".equalsIgnoreCase(activity.getIndoorOutdoor())) {
                tags.add(activity.getIndoorOutdoor());
            }
            if (activity.isFamilyFriendly()) {
                tags.add("family-friendly");
            }
            if (activity.isFoodExperience()) {
                tags.add("food");
            }
            if (activity.isLocalExperience()) {
                tags.add("local");
            }
            if (!tags.isEmpty()) {
                sb.append(" (").append(String.join(", ", tags)).append(')');
            }
        }
        return sb.toString();
    }
}
