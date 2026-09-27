package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Intent / requirements plan produced before the Planner extracts trip slots.
 */
public class IntentPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String TRIP_PLANNING = "TRIP_PLANNING";
    public static final String FLIGHT_SEARCH = "FLIGHT_SEARCH";
    public static final String HOTEL_SEARCH = "HOTEL_SEARCH";
    public static final String RESEARCH = "RESEARCH";
    public static final String WEATHER = "WEATHER";

    private String requestType = "GENERAL";
    private boolean needsFlights = false;
    private boolean needsHotels = false;
    private boolean needsResearch = false;
    private boolean needsWeather = false;
    private boolean needsBudget = false;
    /** Semantic scope of a monetary constraint: NONE, TRIP, HOTEL, FLIGHT, ACTIVITY, OTHER. */
    private String budgetScope = "NONE";
    private boolean needsItinerary = false;
    private boolean needsKnowledge = false;
    /** Request asks the agent to retrieve a previously saved trip/conversation from memory. */
    private boolean needsHistory = false;
    /** Semantic retrieval policy for history requests. */
    private String historySelection = "APPROVED_RECENT";
    private String strategy = "none";
    private String priority = "none";
    private double confidence = 0.5;

    public static IntentPlan fullTrip() {
        IntentPlan plan = new IntentPlan();
        plan.requestType = TRIP_PLANNING;
        plan.needsFlights = true;
        plan.needsHotels = true;
        plan.needsResearch = true;
        plan.needsWeather = true;
        plan.needsBudget = true;
        plan.budgetScope = "TRIP";
        plan.needsItinerary = true;
        plan.needsKnowledge = true;
        plan.strategy = "trip_planning";
        plan.priority = "balanced";
        plan.confidence = 0.85;
        return plan;
    }

    public static IntentPlan flightsOnly() {
        IntentPlan plan = new IntentPlan();
        plan.requestType = FLIGHT_SEARCH;
        plan.needsFlights = true;
        plan.needsHotels = false;
        plan.needsResearch = false;
        plan.needsWeather = false;
        plan.needsBudget = false;
        plan.needsItinerary = false;
        plan.needsKnowledge = false;
        plan.strategy = "flight_only";
        plan.priority = "flights";
        plan.confidence = 0.92;
        return plan;
    }

    public static IntentPlan hotelsOnly() {
        IntentPlan plan = new IntentPlan();
        plan.requestType = HOTEL_SEARCH;
        plan.needsFlights = false;
        plan.needsHotels = true;
        plan.needsResearch = false;
        plan.needsWeather = false;
        plan.needsBudget = false;
        plan.needsItinerary = false;
        plan.needsKnowledge = false;
        plan.strategy = "hotel_only";
        plan.priority = "hotels";
        plan.confidence = 0.9;
        return plan;
    }

    public static IntentPlan researchOnly() {
        IntentPlan plan = new IntentPlan();
        plan.requestType = RESEARCH;
        plan.needsFlights = false;
        plan.needsHotels = false;
        plan.needsResearch = true;
        plan.needsWeather = false;
        plan.needsBudget = false;
        plan.needsItinerary = false;
        plan.strategy = "research_weather";
        plan.priority = "research";
        plan.confidence = 0.9;
        return plan;
    }

    public static IntentPlan weatherOnly() {
        IntentPlan plan = new IntentPlan();
        plan.requestType = WEATHER;
        plan.needsFlights = false;
        plan.needsHotels = false;
        plan.needsResearch = false;
        plan.needsWeather = true;
        plan.needsBudget = false;
        plan.needsItinerary = false;
        plan.needsKnowledge = false;
        plan.strategy = "weather_only";
        plan.priority = "weather";
        plan.confidence = 0.92;
        return plan;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public boolean isNeedsFlights() {
        return needsFlights;
    }

    public void setNeedsFlights(boolean needsFlights) {
        this.needsFlights = needsFlights;
    }

    public boolean isNeedsHotels() {
        return needsHotels;
    }

    public void setNeedsHotels(boolean needsHotels) {
        this.needsHotels = needsHotels;
    }

    public boolean isNeedsResearch() {
        return needsResearch;
    }

    public void setNeedsResearch(boolean needsResearch) {
        this.needsResearch = needsResearch;
    }

    public boolean isNeedsWeather() {
        return needsWeather;
    }

    public void setNeedsWeather(boolean needsWeather) {
        this.needsWeather = needsWeather;
    }

    public boolean isNeedsBudget() {
        return needsBudget;
    }

    public void setNeedsBudget(boolean needsBudget) {
        this.needsBudget = needsBudget;
    }

    public String getBudgetScope() {
        return budgetScope;
    }

    public void setBudgetScope(String budgetScope) {
        this.budgetScope = budgetScope == null || budgetScope.isBlank() ? "NONE" : budgetScope.toUpperCase();
    }

    public boolean isNeedsKnowledge() { return needsKnowledge; }

    public void setNeedsKnowledge(boolean needsKnowledge) { this.needsKnowledge = needsKnowledge; }

    public boolean isNeedsHistory() { return needsHistory; }

    public void setNeedsHistory(boolean needsHistory) { this.needsHistory = needsHistory; }

    public String getHistorySelection() { return historySelection; }

    public void setHistorySelection(String historySelection) {
        this.historySelection = historySelection == null || historySelection.isBlank()
                ? "APPROVED_RECENT"
                : historySelection.toUpperCase();
    }

    public boolean isNeedsItinerary() {
        return needsItinerary;
    }

    public void setNeedsItinerary(boolean needsItinerary) {
        this.needsItinerary = needsItinerary;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String summary() {
        return requestType + " flights=" + needsFlights + " hotels=" + needsHotels
                + " research=" + needsResearch + " weather=" + needsWeather
                + " budget=" + needsBudget + " budgetScope=" + budgetScope + " itinerary=" + needsItinerary + " knowledge=" + needsKnowledge + " history=" + needsHistory
                + " confidence=" + confidence;
    }
}
