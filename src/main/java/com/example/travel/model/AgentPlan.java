package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical execution plan for one user turn. It replaces scattered boolean
 * decisions as the orchestration source of truth while keeping legacy flags
 * available for graph/checkpoint compatibility.
 */
public class AgentPlan implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private String goal = "GENERAL";
    private int version = 1;
    private boolean selectiveExecution;
    private String strategy = "";
    private String priority = "balanced";
    private List<String> actions = new ArrayList<>();
    private List<AgentTask> tasks = new ArrayList<>();
    private List<String> successCriteria = new ArrayList<>();

    public AgentPlan() {}

    public static AgentPlan fromIntent(IntentPlan intent) {
        AgentPlan plan = new AgentPlan();
        if (intent == null) return plan;

        // IMPORTANT: never expand a specialist objective into a complete trip.
        // The user's semantic intent is the contract; dependencies are added only
        // when the requested objective genuinely requires them.
        boolean fullTrip = IntentPlan.TRIP_PLANNING.equalsIgnoreCase(intent.getRequestType());
        plan.goal = intent.getRequestType();
        plan.selectiveExecution = false;
        plan.strategy = intent.getStrategy();
        plan.priority = intent.getPriority();

        if (fullTrip) {
            plan.tasks.add(new AgentTask("flights", "flight", true));
            plan.tasks.add(new AgentTask("hotels", "hotel", true));
            plan.tasks.add(new AgentTask("research", "research", true));
            plan.tasks.add(new AgentTask("weather", "weather", true));
            plan.tasks.add(new AgentTask("budget", "budget", true, "flights", "hotels"));
            plan.tasks.add(new AgentTask("itinerary", "itinerary", true,
                    "flights", "hotels", "research", "weather", "budget"));
            if (intent.isNeedsKnowledge()) plan.tasks.add(new AgentTask("knowledge", "rag", false));
        } else {
            if (intent.isNeedsFlights()) plan.tasks.add(new AgentTask("flights", "flight", true));
            if (intent.isNeedsHotels()) plan.tasks.add(new AgentTask("hotels", "hotel", true));
            if (intent.isNeedsResearch()) plan.tasks.add(new AgentTask("research", "research", true));
            if (intent.isNeedsWeather()) plan.tasks.add(new AgentTask("weather", "weather", true));
            if (intent.isNeedsBudget()) plan.tasks.add(new AgentTask("budget", "budget", true));
            if (intent.isNeedsItinerary()) plan.tasks.add(new AgentTask("itinerary", "itinerary", true));
            if (intent.isNeedsKnowledge()) plan.tasks.add(new AgentTask("knowledge", "rag", true));
            if (intent.isNeedsHistory()) plan.tasks.add(new AgentTask("history", "history", true));
        }
        plan.successCriteria = defaultCriteria(plan);
        return plan;
    }

    private static List<String> defaultCriteria(AgentPlan plan) {
        List<String> criteria = new ArrayList<>();
        for (AgentTask task : plan.tasks) {
            if (task.isRequired()) criteria.add(task.getId() + " outcome");
        }
        return criteria;
    }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal == null ? "GENERAL" : goal; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public boolean isSelectiveExecution() { return selectiveExecution; }
    public void setSelectiveExecution(boolean selectiveExecution) { this.selectiveExecution = selectiveExecution; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy == null ? "" : strategy; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority == null ? "balanced" : priority; }
    public List<String> getActions() { return actions; }
    public void setActions(List<String> actions) { this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions); }
    public List<AgentTask> getTasks() { return tasks; }
    public List<String> getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(List<String> criteria) { this.successCriteria = criteria == null ? new ArrayList<>() : new ArrayList<>(criteria); }
    public void setTasks(List<AgentTask> tasks) { this.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks); }

    public AgentTask task(String id) {
        return tasks.stream().filter(t -> Objects.equals(t.getId(), id)).findFirst().orElse(null);
    }

    public boolean has(String id) { return task(id) != null; }

    public List<AgentTask> preSupervisorTasks() {
        return tasks.stream()
                .filter(t -> List.of("flights", "hotels", "research", "weather").contains(t.getId()))
                .filter(t -> !selectiveExecution
                        ? (t.getStatus() == AgentTask.Status.PENDING || t.getStatus() == AgentTask.Status.READY)
                        : t.getStatus() == AgentTask.Status.READY)
                .toList();
    }

    /**
     * Narrow the current recovery pass and automatically include downstream
     * dependents. This is dependency closure, not a hard-coded list of nodes:
     * recovering a hotel therefore re-runs budget and itinerary when those
     * tasks are part of the original goal.
     */
    public void selectForExecution(List<String> taskIds) {
        selectiveExecution = true;
        java.util.Set<String> selected = new java.util.LinkedHashSet<>(
                taskIds == null ? List.of() : taskIds);

        // Validate the requested recovery set. Unknown task ids must not create
        // a phantom recovery branch.
        selected.removeIf(id -> task(id) == null);

        // Preserve successful work. Only explicitly selected tasks are reopened;
        // downstream tasks are invalidated but remain PENDING until their new
        // upstream evidence has been produced. This prevents a replan from
        // exposing stale derived results as executable.
        tasks.forEach(task -> {
            if (selected.contains(task.getId())) {
                // The failed capability is the recovery root. Re-open it so the
                // execution engine can make a fresh attempt.
                task.setStatus(AgentTask.Status.READY);
                return;
            }

            // IMPORTANT: dependency invalidation must happen BEFORE the
            // "already succeeded" preservation check. A successful downstream
            // task is no longer trustworthy when one of its prerequisites is
            // being re-executed. Re-open it automatically. This keeps recovery
            // dynamic instead of hard-coding flights -> budget -> itinerary,
            // etc.
            if (dependsTransitivelyOn(task.getId(), selected)) {
                task.setStatus(AgentTask.Status.PENDING);
                return;
            }

            if (task.getStatus() == AgentTask.Status.SUCCEEDED) {
                // Preserve successful work that is independent of the recovery
                // root.
                return;
            }

            task.setStatus(AgentTask.Status.SKIPPED);
        });
        version++;
    }

    private boolean dependsTransitivelyOn(String taskId, java.util.Set<String> selected) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        AgentTask root = task(taskId);
        if (root == null) return false;
        queue.addAll(root.getDependsOn());
        while (!queue.isEmpty()) {
            String depId = queue.removeFirst();
            if (!visited.add(depId)) continue;
            if (selected.contains(depId)) return true;
            AgentTask dep = task(depId);
            if (dep != null) queue.addAll(dep.getDependsOn());
        }
        return false;
    }

    /**
     * Canonical execution guard. Legacy RUN_* flags must not be consulted by nodes
     * when an AgentPlan exists. Initial plans execute PENDING/READY tasks; a
     * selective recovery pass executes only READY tasks.
     */
    public boolean shouldExecute(String taskId) {
        AgentTask task = task(taskId);
        if (task == null) return false;
        return selectiveExecution
                ? task.getStatus() == AgentTask.Status.READY
                : task.getStatus() == AgentTask.Status.PENDING
                    || task.getStatus() == AgentTask.Status.READY;
    }

    /** Return the executable pre-supervisor agents for the current pass. */
    public List<String> executablePreSupervisorAgents() {
        return preSupervisorTasks().stream().map(AgentTask::getAgent).toList();
    }

    public boolean ready(String id) {
        AgentTask task = task(id);
        if (task == null) return false;
        // A failed/running/succeeded task is never "ready". This prevents the
        // execution node from accidentally retrying a failed task in the same
        // graph pass and bypassing the global replan budget.
        if (task.getStatus() != AgentTask.Status.PENDING
                && task.getStatus() != AgentTask.Status.READY) {
            return false;
        }
        return task.getDependsOn().stream().allMatch(dep -> {
            AgentTask dependency = task(dep);
            // Unknown dependencies are invalid, not automatically satisfied.
            // Silent dependency removal is dangerous because it can make budget
            // or itinerary run without the evidence they require.
            return dependency != null && dependency.getStatus() == AgentTask.Status.SUCCEEDED;
        });
    }

    public void markStarted(String id) {
        AgentTask task = task(id);
        if (task != null && (task.getStatus() == AgentTask.Status.PENDING
                || task.getStatus() == AgentTask.Status.READY
                || task.getStatus() == AgentTask.Status.FAILED)) {
            task.beginAttempt();
            task.setStatus(AgentTask.Status.RUNNING);
        }
    }

    public void markSucceeded(String id) {
        AgentTask task = task(id);
        if (task != null) task.setStatus(AgentTask.Status.SUCCEEDED);
    }

    public void markFailed(String id, String reason) {
        AgentTask task = task(id);
        if (task != null) {
            task.setStatus(AgentTask.Status.FAILED);
            task.setFailureReason(reason);
        }
    }

    public boolean requiredWorkComplete() {
        return tasks.stream().filter(AgentTask::isRequired)
                .allMatch(t -> t.getStatus() == AgentTask.Status.SUCCEEDED || t.getStatus() == AgentTask.Status.SKIPPED);
    }

    public boolean hasRetryableFailure(int maxAttempts) {
        return tasks.stream().anyMatch(t -> t.isRequired()
                && t.getStatus() == AgentTask.Status.FAILED
                && t.getAttempts() < maxAttempts);
    }
    /** All required failures currently eligible for an explicit recovery pass. */
    public List<String> failedRequiredTaskIds() {
        return tasks.stream()
                .filter(AgentTask::isRequired)
                .filter(t -> t.getStatus() == AgentTask.Status.FAILED)
                .map(AgentTask::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** True when every required capability has a terminal successful outcome. */
    public boolean allRequiredTasksSucceeded() {
        return tasks.stream()
                .filter(AgentTask::isRequired)
                .allMatch(t -> t.getStatus() == AgentTask.Status.SUCCEEDED);
    }


    @Override public String toString() {
        return "AgentPlan{goal='" + goal + "', version=" + version + ", tasks=" + tasks + '}';
    }
}
