package com.example.travel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_preference")
public class UserPreference {

    @Id
    @Column(length = 80)
    private String userId;

    @Column(length = 8)
    private String preferredAirport;

    @Column(length = 40)
    private String travelStyle;

    @Column(length = 8)
    private String currency;

    @Column(length = 8)
    private String preferredHotelRating;

    @Column(length = 80)
    private String lastDestination;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPreferredAirport() {
        return preferredAirport;
    }

    public void setPreferredAirport(String preferredAirport) {
        this.preferredAirport = preferredAirport;
    }

    public String getTravelStyle() {
        return travelStyle;
    }

    public void setTravelStyle(String travelStyle) {
        this.travelStyle = travelStyle;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPreferredHotelRating() {
        return preferredHotelRating;
    }

    public void setPreferredHotelRating(String preferredHotelRating) {
        this.preferredHotelRating = preferredHotelRating;
    }

    public String getLastDestination() {
        return lastDestination;
    }

    public void setLastDestination(String lastDestination) {
        this.lastDestination = lastDestination;
    }
}
