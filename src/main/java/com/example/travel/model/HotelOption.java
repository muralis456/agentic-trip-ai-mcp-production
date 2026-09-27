package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

public class HotelOption implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String area;
    private String priceRange;
    private String rating;
    private String suitableFor;
    private String notes;
    private String imageUrl;
    private String bookingUrl;
    private String amenities;
    private String hotelClass;
    private int reviews;
    private String totalPrice;
    private String currency;
    private String deal;
    private boolean freeCancellation;
    private String propertyToken;
    private String provider;

    public HotelOption() {
    }

    public HotelOption(String name, String area, String priceRange, String notes) {
        this.name = name;
        this.area = area;
        this.priceRange = priceRange;
        this.notes = notes;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getPriceRange() {
        return priceRange;
    }

    public void setPriceRange(String priceRange) {
        this.priceRange = priceRange;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getSuitableFor() {
        return suitableFor;
    }

    public void setSuitableFor(String suitableFor) {
        this.suitableFor = suitableFor;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }


    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getBookingUrl() { return bookingUrl; }
    public void setBookingUrl(String bookingUrl) { this.bookingUrl = bookingUrl; }
    public String getAmenities() { return amenities; }
    public void setAmenities(String amenities) { this.amenities = amenities; }
    public String getHotelClass() { return hotelClass; }
    public void setHotelClass(String hotelClass) { this.hotelClass = hotelClass; }
    public int getReviews() { return reviews; }
    public void setReviews(int reviews) { this.reviews = reviews; }
    public String getTotalPrice() { return totalPrice; }
    public void setTotalPrice(String totalPrice) { this.totalPrice = totalPrice; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDeal() { return deal; }
    public void setDeal(String deal) { this.deal = deal; }
    public boolean isFreeCancellation() { return freeCancellation; }
    public void setFreeCancellation(boolean freeCancellation) { this.freeCancellation = freeCancellation; }
    public String getPropertyToken() { return propertyToken; }
    public void setPropertyToken(String propertyToken) { this.propertyToken = propertyToken; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isBlank()) {
            sb.append(name);
        }
        if (area != null && !area.isBlank() && !isPlaceholder(area)) {
            sb.append(" (").append(area).append(')');
        }
        if (priceRange != null && !priceRange.isBlank() && !isPlaceholder(priceRange)) {
            sb.append(" — ").append(priceRange);
        }
        if (rating != null && !rating.isBlank() && !isPlaceholder(rating)) {
            sb.append(" · ").append(rating);
        }
        if (notes != null && !notes.isBlank() && !isPlaceholder(notes)) {
            sb.append(". ").append(notes);
        }
        return sb.toString();
    }

    private static boolean isPlaceholder(String value) {
        String normalized = value.trim().toLowerCase();
        return normalized.equals("not specified") || normalized.equals("n/a") || normalized.equals("unknown");
    }
}
