package com.example.travel.model;

import com.example.travel.support.JsonSupport;

import java.io.Serial;
import java.io.Serializable;

public class TravelResearch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String topic;
    private String summary;

    public TravelResearch() {
    }

    public TravelResearch(String topic, String summary) {
        this.topic = topic;
        this.summary = summary;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String toDisplay() {
        String safeSummary = summary == null ? "" : summary.trim();
        if (JsonSupport.looksLikeJsonObject(safeSummary)) {
            // Never dump raw model JSON into the chat UI.
            safeSummary = "See attractions and itinerary for details.";
        }
        if (topic == null || topic.isBlank()) {
            return safeSummary;
        }
        return topic + ": " + safeSummary;
    }
}
