package com.example.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.example.travel.service.ModelRoutingContext;

@ConfigurationProperties(prefix = "travel.models")
public class TravelModelsProperties {

    /**
     * Configured via {@code travel.models.*} in application.yml only — no code defaults.
     */
    private String extraction;
    private String planner;
    private String itinerary;
    private String finale;
    private String fast;
    private String balanced;
    private String reasoning;
    private java.util.List<String> fallbackModels = new java.util.ArrayList<>();

    private double extractionTemperature = 0.1;
    private double plannerTemperature = 0.2;
    private double itineraryTemperature = 0.3;
    private double finaleTemperature = 0.4;

    public String getExtraction() {
        return extraction;
    }

    public void setExtraction(String extraction) {
        this.extraction = extraction;
    }

    public String getPlanner() {
        return planner;
    }

    public void setPlanner(String planner) {
        this.planner = planner;
    }

    public String getItinerary() {
        return itinerary;
    }

    public void setItinerary(String itinerary) {
        this.itinerary = itinerary;
    }

    public String getFinale() {
        return finale;
    }

    public void setFinale(String finale) {
        this.finale = finale;
    }

    public String getFast() {
        return fast;
    }

    public void setFast(String fast) {
        this.fast = fast;
    }

    public String getBalanced() {
        return balanced;
    }

    public void setBalanced(String balanced) {
        this.balanced = balanced;
    }

    public java.util.List<String> getFallbackModels() { return fallbackModels; }

    public void setFallbackModels(java.util.List<String> fallbackModels) { this.fallbackModels = fallbackModels == null ? new java.util.ArrayList<>() : fallbackModels; }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public double getExtractionTemperature() {
        return extractionTemperature;
    }

    public void setExtractionTemperature(double extractionTemperature) {
        this.extractionTemperature = extractionTemperature;
    }

    public double getPlannerTemperature() {
        return plannerTemperature;
    }

    public void setPlannerTemperature(double plannerTemperature) {
        this.plannerTemperature = plannerTemperature;
    }

    public double getItineraryTemperature() {
        return itineraryTemperature;
    }

    public void setItineraryTemperature(double itineraryTemperature) {
        this.itineraryTemperature = itineraryTemperature;
    }

    public double getFinaleTemperature() {
        return finaleTemperature;
    }

    public void setFinaleTemperature(double finaleTemperature) {
        this.finaleTemperature = finaleTemperature;
    }

    public String model(AgentRole role) {
        String configured = switch (role) {
            case PLANNER -> planner;
            case EXTRACT -> extraction;
            case ITINERARY -> itinerary;
            case FINAL -> finale;
        };
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "travel.models." + role.name().toLowerCase()
                            + " is not set. Configure it in application.yml (no model hardcoding in code).");
        }
        return configured.trim();
    }

    /**
     * User policy FAST/BALANCED/REASONING, or a concrete Ollama model id, overlays role defaults.
     */
    public String resolve(AgentRole role, String policy) {
        if (policy == null || policy.isBlank() || "BALANCED".equalsIgnoreCase(policy)) {
            return switch (ModelRoutingContext.getComplexity()) {
                case SIMPLE -> firstNonBlank(fast, model(role));
                case NORMAL -> firstNonBlank(model(role), balanced, fast);
                case COMPLEX -> firstNonBlank(reasoning, model(role), balanced);
            };
        }
        if ("FAST".equalsIgnoreCase(policy)) {
            return firstNonBlank(fast, extraction, model(role));
        }
        if ("REASONING".equalsIgnoreCase(policy)) {
            return firstNonBlank(reasoning, finale, model(role));
        }
        return policy.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public double temperature(AgentRole role) {
        return switch (role) {
            case PLANNER -> plannerTemperature;
            case EXTRACT -> extractionTemperature;
            case ITINERARY -> itineraryTemperature;
            case FINAL -> finaleTemperature;
        };
    }

    public enum AgentRole {
        PLANNER,
        EXTRACT,
        ITINERARY,
        FINAL
    }
}
