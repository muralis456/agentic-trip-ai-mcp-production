package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

public class FlightOption implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String flightNumber;
    private String airline;
    private String origin;
    private String destination;
    private String departureTime;
    private String arrivalTime;
    private String status;
    private String notes;
    private String direction = "outbound";
    private String requestedDate = "";

    public FlightOption() {
    }

    public FlightOption(String flightNumber, String airline, String origin, String destination,
                         String departureTime, String arrivalTime, String status, String notes) {
        this.flightNumber = flightNumber;
        this.airline = airline;
        this.origin = origin;
        this.destination = destination;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.status = status;
        this.notes = notes;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
    }

    public String getAirline() {
        return airline;
    }

    public void setAirline(String airline) {
        this.airline = airline;
    }

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

    public String getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(String departureTime) {
        this.departureTime = departureTime;
    }

    public String getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(String arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction == null ? "outbound" : direction; }
    public String getRequestedDate() { return requestedDate; }
    public void setRequestedDate(String requestedDate) { this.requestedDate = requestedDate == null ? "" : requestedDate; }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        if (airline != null && !airline.isBlank()) {
            sb.append(airline).append(' ');
        }
        if (flightNumber != null && !flightNumber.isBlank()) {
            sb.append(flightNumber);
        }
        if (origin != null && destination != null) {
            sb.append(" ").append(origin).append(" -> ").append(destination);
        }
        if (departureTime != null && !departureTime.isBlank()) {
            sb.append(" dep ").append(departureTime);
        }
        if (notes != null && !notes.isBlank()) {
            sb.append(" (").append(notes).append(')');
        }
        return sb.toString().trim();
    }
}
