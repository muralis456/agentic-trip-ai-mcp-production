package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * First-class unit of work in the travel agent plan. The LLM decides the goal;
 * Java owns execution state, dependencies, retries and completion semantics.
 */
public class AgentTask implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public enum Status { PENDING, READY, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    private String id;
    private String agent;
    private boolean required;
    private Status status = Status.PENDING;
    private int attempts;
    private List<String> dependsOn = new ArrayList<>();
    private String failureReason = "";

    public AgentTask() {}

    public AgentTask(String id, String agent, boolean required, String... dependencies) {
        this.id = id;
        this.agent = agent;
        this.required = required;
        if (dependencies != null) this.dependsOn.addAll(List.of(dependencies));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn == null ? new ArrayList<>() : new ArrayList<>(dependsOn); }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason == null ? "" : failureReason; }

    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.SKIPPED; }

    /** Mark one actual execution attempt. Attempts are counted at start so a
     * failed invocation is still part of the retry budget. */
    public void beginAttempt() { attempts++; }
}
