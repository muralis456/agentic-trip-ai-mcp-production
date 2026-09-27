package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured choice an agent made so the graph can route on decisions, not ad-hoc strings.
 */
public class AgentDecision implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String agent = "";
    private String decision = "";
    private String reason = "";
    private Map<String, String> parameters = new LinkedHashMap<>();
    private double confidence = 1.0;

    public AgentDecision() {
    }

    public AgentDecision(String agent, String decision, String reason, double confidence) {
        this.agent = agent == null ? "" : agent;
        this.decision = decision == null ? "" : decision;
        this.reason = reason == null ? "" : reason;
        this.confidence = confidence;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters == null ? new LinkedHashMap<>() : parameters;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String toDisplay() {
        return agent + ": " + decision + (reason.isBlank() ? "" : " (" + reason + ")");
    }
}
