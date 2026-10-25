package com.example.travel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class TravelRequest {

    @JsonIgnore
    private String userId;
    /** Stable browser/application conversation id. Unlike threadId, this survives follow-up turns. */
    @Size(max = 160, message = "Conversation id must be at most 160 characters.")
    private String conversationId;
    /** Last graph thread attached to the active browser conversation, when available. */
    @Size(max = 160, message = "Continuation thread id must be at most 160 characters.")
    private String continuationThreadId;
    @Size(max = 160, message = "Destination must be at most 160 characters.")
    private String destination;
    @Size(max = 160, message = "Departure city must be at most 160 characters.")
    private String departureCity;
    @Size(max = 32, message = "Departure date must be at most 32 characters.")
    private String departureDate;
    @Size(max = 32, message = "Return date must be at most 32 characters.")
    private String returnDate;
    @Min(value = 1, message = "At least one adult is required.")
    @Max(value = 9, message = "At most nine adults are supported.")
    private Integer adults = 1;
    @Min(value = 0, message = "Children count cannot be negative.")
    @Max(value = 9, message = "At most nine children are supported.")
    private Integer children = 0;
    @Size(max = 80, message = "Budget must be at most 80 characters.")
    private String budget;
    @Size(max = 80, message = "Travel style must be at most 80 characters.")
    private String travelStyle;
    @Size(max = 4_000, message = "Preferences must be at most 4000 characters.")
    private String preferences;
    @Size(max = 120, message = "Selected model must be at most 120 characters.")
    private String selectedModel;
    @Size(max = 8_000, message = "Prompt must be at most 8000 characters.")
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
