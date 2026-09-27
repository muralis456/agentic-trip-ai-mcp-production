package com.example.travel.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * User-facing durable travel knowledge retrieved by the Agentic RAG pipeline.
 * This is intentionally separate from execution/observability metadata.
 */
public class KnowledgeGuidance implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean available;
    private String title = "Travel Knowledge & Guidance";
    private String answer = "";
    private String query = "";
    private String destination = "";
    private String country = "";
    private List<String> topics = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
    private double evidenceScore;

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null || title.isBlank() ? "Travel Knowledge & Guidance" : title; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer == null ? "" : answer; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query == null ? "" : query; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination == null ? "" : destination; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country == null ? "" : country; }
    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics); }
    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources); }
    public double getEvidenceScore() { return evidenceScore; }
    public void setEvidenceScore(double evidenceScore) { this.evidenceScore = evidenceScore; }
}
