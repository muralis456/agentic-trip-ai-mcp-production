package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Itinerary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String summary;
    private List<ItineraryDay> days = new ArrayList<>();
    private String provider = "";

    public Itinerary() {
    }

    public Itinerary(String summary, List<ItineraryDay> days) {
        this.summary = summary;
        this.days = days != null ? days : new ArrayList<>();
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<ItineraryDay> getDays() {
        return days;
    }

    public void setDays(List<ItineraryDay> days) {
        this.days = days;
    }

    public String getProvider() { return provider; }

    public void setProvider(String provider) { this.provider = provider == null ? "" : provider; }

    public boolean isEmpty() {
        return (summary == null || summary.isBlank()) && (days == null || days.isEmpty());
    }

    public String toDisplay() {
        if (days != null && !days.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (summary != null && !summary.isBlank()) {
                sb.append(summary).append('\n');
            }
            for (ItineraryDay day : days) {
                sb.append("Day ").append(day.getDay());
                if (day.getTitle() != null && !day.getTitle().isBlank()) {
                    sb.append(" — ").append(day.getTitle());
                }
                sb.append('\n');
                String text = day.activitiesText();
                if (!text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
            return sb.toString().trim();
        }
        return summary == null ? "" : summary;
    }
}
