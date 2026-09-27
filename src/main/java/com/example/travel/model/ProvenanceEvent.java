package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

public class ProvenanceEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String field = "";
    private String source = "";
    private String url = "";
    private String retrievedAt = Instant.now().toString();
    private double confidence = 1.0;
    private String note = "";

    public ProvenanceEvent() {
    }

    public ProvenanceEvent(String field, String source, String url, double confidence, String note) {
        this.field = field == null ? "" : field;
        this.source = source == null ? "" : source;
        this.url = url == null ? "" : url;
        this.confidence = confidence;
        this.note = note == null ? "" : note;
        this.retrievedAt = Instant.now().toString();
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getRetrievedAt() {
        return retrievedAt;
    }

    public void setRetrievedAt(String retrievedAt) {
        this.retrievedAt = retrievedAt;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(field).append(": ").append(source);
        if (note != null && !note.isBlank()) {
            sb.append(" — ").append(note);
        }
        if (url != null && !url.isBlank()) {
            sb.append('\n').append(url);
        }
        if (retrievedAt != null && !retrievedAt.isBlank()) {
            sb.append("\nRetrieved: ").append(retrievedAt);
        }
        if (confidence > 0 && url != null && !url.isBlank()) {
            sb.append("\nTavily score: ").append(String.format("%.2f", confidence));
        }
        return sb.toString();
    }
}
