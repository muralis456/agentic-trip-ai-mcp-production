package com.example.travel.dto;

import com.example.travel.model.AgentStep;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal LangGraph observability — not shown in the main trip presentation.
 */
public class AgentExecutionDetails implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<AgentStep> timeline = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
    private String executionHistory = "";
    private boolean ragUsed;
    private String ragDecision = "";
    private String ragQuery = "";
    private String ragAnswer = "";
    private List<String> ragSources = new ArrayList<>();
    private double ragEvidenceScore;
    private int ragCandidateCount;
    private int ragRerankedCount;
    private int ragIterations;
    private String ragRetrievalMethod = "none";

    public List<AgentStep> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<AgentStep> timeline) {
        this.timeline = timeline == null ? new ArrayList<>() : timeline;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources == null ? new ArrayList<>() : sources;
    }

    public String getExecutionHistory() {
        return executionHistory;
    }

    public void setExecutionHistory(String executionHistory) {
        this.executionHistory = executionHistory == null ? "" : executionHistory;
    }

    public boolean isRagUsed() { return ragUsed; }
    public void setRagUsed(boolean ragUsed) { this.ragUsed = ragUsed; }

    public String getRagDecision() { return ragDecision; }
    public void setRagDecision(String ragDecision) { this.ragDecision = ragDecision == null ? "" : ragDecision; }

    public String getRagQuery() { return ragQuery; }
    public void setRagQuery(String ragQuery) { this.ragQuery = ragQuery == null ? "" : ragQuery; }

    public String getRagAnswer() { return ragAnswer; }
    public void setRagAnswer(String ragAnswer) { this.ragAnswer = ragAnswer == null ? "" : ragAnswer; }

    public List<String> getRagSources() { return ragSources; }
    public void setRagSources(List<String> ragSources) { this.ragSources = ragSources == null ? new ArrayList<>() : ragSources; }

    public double getRagEvidenceScore() { return ragEvidenceScore; }
    public void setRagEvidenceScore(double ragEvidenceScore) { this.ragEvidenceScore = ragEvidenceScore; }

    public int getRagCandidateCount() { return ragCandidateCount; }
    public void setRagCandidateCount(int ragCandidateCount) { this.ragCandidateCount = ragCandidateCount; }

    public int getRagRerankedCount() { return ragRerankedCount; }
    public void setRagRerankedCount(int ragRerankedCount) { this.ragRerankedCount = ragRerankedCount; }

    public int getRagIterations() { return ragIterations; }
    public void setRagIterations(int ragIterations) { this.ragIterations = ragIterations; }

    public String getRagRetrievalMethod() { return ragRetrievalMethod; }
    public void setRagRetrievalMethod(String ragRetrievalMethod) { this.ragRetrievalMethod = ragRetrievalMethod == null ? "none" : ragRetrievalMethod; }

}
