package com.example.travel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class TravelRequest {

    @JsonIgnore
    private String userId;
    /** Stable browser/application conversation id. Unlike threadId, this survives follow-up turns. */
    private String conversationId;
    /** Last graph thread attached to the active browser conversation, when available. */
    private String continuationThreadId;
    private String destination;
    private String departureCity;
    private String departureDate;
    private String returnDate;
    private Integer adults = 1;
    private Integer children = 0;
    private String budget;
    private String travelStyle;
    private String preferences;
    private String selectedModel;
    private String prompt;

    /** Internal copy of the user's raw prompt before query normalization. */
    @JsonIgnore
    private String originalPrompt;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getContinuationThreadId() {
        return continuationThreadId;
    }

    public void setContinuationThreadId(String continuationThreadId) {
        this.continuationThreadId = continuationThreadId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getDepartureCity() {
        return departureCity;
    }

    public void setDepartureCity(String departureCity) {
        this.departureCity = departureCity;
    }

    public String getDepartureDate() {
        return departureDate;
    }

    public void setDepartureDate(String departureDate) {
        this.departureDate = departureDate;
    }

    public String getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(String returnDate) {
        this.returnDate = returnDate;
    }

    public Integer getAdults() {
        return adults;
    }

    public void setAdults(Integer adults) {
        this.adults = adults;
    }

    public Integer getChildren() {
        return children;
    }

    public void setChildren(Integer children) {
        this.children = children;
    }

    public String getBudget() {
        return budget;
    }

    public void setBudget(String budget) {
        this.budget = budget;
    }

    public String getTravelStyle() {
        return travelStyle;
    }

    public void setTravelStyle(String travelStyle) {
        this.travelStyle = travelStyle;
    }

    public String getPreferences() {
        return preferences;
    }

    public void setPreferences(String preferences) {
        this.preferences = preferences;
    }

    public String getSelectedModel() {
        return selectedModel;
    }

    public void setSelectedModel(String selectedModel) {
        this.selectedModel = selectedModel;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getOriginalPrompt() {
        return originalPrompt;
    }

    public void setOriginalPrompt(String originalPrompt) {
        this.originalPrompt = originalPrompt;
    }
}
