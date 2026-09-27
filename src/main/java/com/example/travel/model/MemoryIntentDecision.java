package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Semantic decision about whether the current request should read persistent
 * user memory. This is deliberately separate from travel-domain capabilities.
 */
public class MemoryIntentDecision implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean needsHistory;
    private boolean historyOnly;
    /** Semantic selection policy for memory retrieval. */
    private String selection = "APPROVED_RECENT";
    private double confidence;

    public MemoryIntentDecision() {
    }

    public MemoryIntentDecision(boolean needsHistory, boolean historyOnly, double confidence) {
        this(needsHistory, historyOnly, "APPROVED_RECENT", confidence);
    }

    public MemoryIntentDecision(boolean needsHistory, boolean historyOnly, String selection, double confidence) {
        this.needsHistory = needsHistory;
        this.historyOnly = historyOnly;
        this.selection = selection == null || selection.isBlank() ? "APPROVED_RECENT" : selection;
        this.confidence = confidence;
    }

    public boolean isNeedsHistory() {
        return needsHistory;
    }

    public void setNeedsHistory(boolean needsHistory) {
        this.needsHistory = needsHistory;
    }

    public boolean isHistoryOnly() {
        return historyOnly;
    }

    public void setHistoryOnly(boolean historyOnly) {
        this.historyOnly = historyOnly;
    }

    public String getSelection() {
        return selection;
    }

    public void setSelection(String selection) {
        this.selection = selection == null || selection.isBlank() ? "APPROVED_RECENT" : selection;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }
}
