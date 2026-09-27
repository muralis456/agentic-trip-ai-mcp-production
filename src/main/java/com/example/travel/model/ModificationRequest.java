package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Structured human modification so Modify is not hardcoded to "make it cheaper".
 */
public class ModificationRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String REDUCE_COST = "REDUCE_COST";
    public static final String HOTEL_UPGRADE = "HOTEL_UPGRADE";
    public static final String ADD_DESTINATION = "ADD_DESTINATION";
    public static final String ITINERARY_CHANGE = "ITINERARY_CHANGE";
    public static final String GENERAL = "GENERAL";

    private String changeType = GENERAL;
    private String destination = "";
    private Integer days;
    private String targetRating = "";
    private boolean preserveBudget = true;
    private String notes = "";
    private java.math.BigDecimal hotelBudget;
    private String flightPreference = "";

    public java.math.BigDecimal getHotelBudget() {
        return hotelBudget;
    }

    public void setHotelBudget(java.math.BigDecimal hotelBudget) {
        this.hotelBudget = hotelBudget;
    }

    public String getFlightPreference() {
        return flightPreference;
    }

    public void setFlightPreference(String flightPreference) {
        this.flightPreference = flightPreference == null ? "" : flightPreference;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType == null || changeType.isBlank() ? GENERAL : changeType;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination == null ? "" : destination;
    }

    public Integer getDays() {
        return days;
    }

    public void setDays(Integer days) {
        this.days = days;
    }

    public String getTargetRating() {
        return targetRating;
    }

    public void setTargetRating(String targetRating) {
        this.targetRating = targetRating == null ? "" : targetRating;
    }

    public boolean isPreserveBudget() {
        return preserveBudget;
    }

    public void setPreserveBudget(boolean preserveBudget) {
        this.preserveBudget = preserveBudget;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    public boolean isReduceCost() {
        return REDUCE_COST.equalsIgnoreCase(changeType);
    }

    public boolean isHotelCostReduction() {
        String text = notes == null ? "" : notes.toLowerCase(Locale.ROOT);
        return isReduceCost() && text.contains("hotel")
                && containsAny(text, "price", "prices", "rate", "rates", "cost", "costs", "expensive", "high");
    }

    public boolean isFlightCostReduction() {
        String text = notes == null ? "" : notes.toLowerCase(Locale.ROOT);
        return isReduceCost() && text.contains("flight")
                && containsAny(text, "price", "prices", "fare", "fares", "cost", "costs", "expensive", "high");
    }

    public boolean isHotelUpgrade() {
        return HOTEL_UPGRADE.equalsIgnoreCase(changeType);
    }

    public boolean isAddDestination() {
        return ADD_DESTINATION.equalsIgnoreCase(changeType);
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
