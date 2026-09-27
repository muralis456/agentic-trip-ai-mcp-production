package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Strict JSON contract used by the planning LLM. */
public class PlanExtraction implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String goal = "";
    private String strategy = "";
    private String priority = "balanced";
    private List<String> actions = new ArrayList<>();
    private List<String> successCriteria = new ArrayList<>();
    private List<PlannedTask> tasks = new ArrayList<>();

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal == null ? "" : goal; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy == null ? "" : strategy; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority == null ? "balanced" : priority; }
    public List<String> getActions() { return actions; }
    public void setActions(List<String> v) { actions = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<String> getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(List<String> v) { successCriteria = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public List<PlannedTask> getTasks() { return tasks; }
    public void setTasks(List<PlannedTask> v) { tasks = v == null ? new ArrayList<>() : new ArrayList<>(v); }

    public static class PlannedTask implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private String id = "";
        private String agent = "";
        private boolean required = true;
        private List<String> dependsOn = new ArrayList<>();
        public String getId() { return id; }
        public void setId(String id) { this.id = id == null ? "" : id; }
        public String getAgent() { return agent; }
        public void setAgent(String agent) { this.agent = agent == null ? "" : agent; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public List<String> getDependsOn() { return dependsOn; }
        public void setDependsOn(List<String> v) { dependsOn = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    }
}
