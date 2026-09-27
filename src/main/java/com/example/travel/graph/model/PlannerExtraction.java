package com.example.travel.graph.model;

import java.io.Serializable;

public class PlannerExtraction implements Serializable {

    private String origin;
    private String destination;
    private String departureDate;
    private String returnDate;
    private Integer travelers;
    private String budget;
    private String hotelBudget;
    private String travelStyle;

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
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

    public Integer getTravelers() {
        return travelers;
    }

    public void setTravelers(Integer travelers) {
        this.travelers = travelers;
    }

    public String getBudget() {
        return budget;
    }

    public void setBudget(String budget) {
        this.budget = budget;
    }

    public String getHotelBudget() {
        return hotelBudget;
    }

    public void setHotelBudget(String hotelBudget) {
        this.hotelBudget = hotelBudget;
    }

    public String getTravelStyle() {
        return travelStyle;
    }

    public void setTravelStyle(String travelStyle) {
        this.travelStyle = travelStyle;
    }
}
