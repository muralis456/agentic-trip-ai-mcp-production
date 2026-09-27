package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ReplanStrategy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String reason = "";
    private Double targetReduction;
    private double expectedImpact;
    private List<String> actions = new ArrayList<>();
    private List<ReplanAction> resolvedActions = new ArrayList<>();
    private String priority = "itinerary";

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    public Double getTargetReduction() {
        return targetReduction;
    }

    public void setTargetReduction(Double targetReduction) {
        this.targetReduction = targetReduction;
    }

    public double getExpectedImpact() {
        return expectedImpact;
    }

    public void setExpectedImpact(double expectedImpact) {
        this.expectedImpact = expectedImpact;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions == null
                ? new ArrayList<>()
                : new ArrayList<>(actions);
    }

    public List<ReplanAction> getResolvedActions() {
        return resolvedActions;
    }

    public void setResolvedActions(List<ReplanAction> resolvedActions) {
        this.resolvedActions = resolvedActions == null
                ? new ArrayList<>()
                : new ArrayList<>(resolvedActions);
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = TravelStateSafeString.firstNonBlank(
                priority,
                "itinerary");
    }

    public boolean hasAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }

        String needle = action.trim().toLowerCase();

        if (actions != null) {
            for (String item : actions) {
                if (item != null
                        && item.trim().toLowerCase().equals(needle)) {
                    return true;
                }
            }
        }

        if (resolvedActions != null) {
            for (ReplanAction resolved : resolvedActions) {
                if (resolved != null
                        && resolved.wireName().equalsIgnoreCase(needle)) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean hasResolved(ReplanAction action) {
        return action != null
                && resolvedActions != null
                && resolvedActions.contains(action);
    }

    /**
     * Small local helper to avoid introducing another dependency.
     */
    private static final class TravelStateSafeString {

        private TravelStateSafeString() {
        }

        static String firstNonBlank(String value, String fallback) {
            return value == null || value.isBlank()
                    ? fallback
                    : value;
        }
    }
}