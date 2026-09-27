package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

public class PlanQualityScore implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final double PASS_THRESHOLD = 0.80;

    private double budget = 1.0;
    private double flight = 1.0;
    private double hotel = 1.0;
    private double itinerary = 1.0;
    private double preferences = 1.0;
    private double weather = 1.0;
    private double overall = 1.0;

    public double getBudget() {
        return budget;
    }

    public void setBudget(double budget) {
        this.budget = clamp(budget);
    }

    public double getFlight() {
        return flight;
    }

    public void setFlight(double flight) {
        this.flight = clamp(flight);
    }

    public double getHotel() {
        return hotel;
    }

    public void setHotel(double hotel) {
        this.hotel = clamp(hotel);
    }

    public double getItinerary() {
        return itinerary;
    }

    public void setItinerary(double itinerary) {
        this.itinerary = clamp(itinerary);
    }

    public double getPreferences() {
        return preferences;
    }

    public void setPreferences(double preferences) {
        this.preferences = clamp(preferences);
    }

    public double getWeather() {
        return weather;
    }

    public void setWeather(double weather) {
        this.weather = clamp(weather);
    }

    public double getOverall() {
        return overall;
    }

    public void setOverall(double overall) {
        this.overall = clamp(overall);
    }

    public boolean passes() {
        return overall >= PASS_THRESHOLD;
    }

    public void recomputeOverall() {
        overall = clamp((budget + flight + hotel + itinerary + preferences + weather) / 6.0);
    }

    /**
     * Requirement-aware weighted score when the user explicitly asked for family/food/local experiences.
     */
    public void recomputeWeighted(TripRequirements requirements) {
        if (requirements == null || !requirements.hasExplicitPreferences()) {
            recomputeOverall();
            return;
        }
        double wFamily = requirements.isFamilyFriendly() ? 0.25 : 0.0;
        double wLocal = requirements.isLocalExperiences() ? 0.20 : 0.0;
        double wFood = requirements.isFoodExperiences() ? 0.15 : 0.0;
        double wBudget = requirements.isBudgetConscious() ? 0.20 : 0.15;
        double wItinerary = 0.10;
        double wWeather = 0.10;
        double wFlight = 0.10;
        double wHotel = 0.10;
        double prefWeight = wFamily + wLocal + wFood;
        if (prefWeight <= 0.0) {
            prefWeight = 0.15;
        }
        double weightSum = wBudget + wItinerary + wWeather + wFlight + wHotel + prefWeight;
        overall = clamp((budget * wBudget
                + flight * wFlight
                + hotel * wHotel
                + itinerary * wItinerary
                + preferences * prefWeight
                + weather * wWeather) / weightSum);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
