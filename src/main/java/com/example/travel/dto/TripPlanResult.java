package com.example.travel.dto;

import com.example.travel.model.BudgetSummary;
import com.example.travel.model.FlightOption;
import com.example.travel.model.HotelOption;
import com.example.travel.model.Itinerary;
import com.example.travel.model.WeatherForecast;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain presentation model assembled from graph state (not raw LangGraph internals).
 */
public class TripPlanResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private TripHeader trip = new TripHeader();
    private List<FlightOption> flights = new ArrayList<>();
    private List<HotelOption> hotels = new ArrayList<>();
    private Itinerary itinerary = new Itinerary();
    private BudgetSummary budget = new BudgetSummary();
    private WeatherForecast weather = new WeatherForecast("", "", false);
    private PlanValidationView validation = new PlanValidationView();
    private String tips = "";
    private KnowledgeGuidance knowledge = new KnowledgeGuidance();

    public TripHeader getTrip() {
        return trip;
    }

    public void setTrip(TripHeader trip) {
        this.trip = trip == null ? new TripHeader() : trip;
    }

    public List<FlightOption> getFlights() {
        return flights;
    }

    public void setFlights(List<FlightOption> flights) {
        this.flights = flights == null ? new ArrayList<>() : flights;
    }

    public List<HotelOption> getHotels() {
        return hotels;
    }

    public void setHotels(List<HotelOption> hotels) {
        this.hotels = hotels == null ? new ArrayList<>() : hotels;
    }

    public Itinerary getItinerary() {
        return itinerary;
    }

    public void setItinerary(Itinerary itinerary) {
        this.itinerary = itinerary == null ? new Itinerary() : itinerary;
    }

    public BudgetSummary getBudget() {
        return budget;
    }

    public void setBudget(BudgetSummary budget) {
        this.budget = budget == null ? new BudgetSummary() : budget;
    }

    public WeatherForecast getWeather() {
        return weather;
    }

    public void setWeather(WeatherForecast weather) {
        this.weather = weather == null ? new WeatherForecast("", "", false) : weather;
    }

    public PlanValidationView getValidation() {
        return validation;
    }

    public void setValidation(PlanValidationView validation) {
        this.validation = validation == null ? new PlanValidationView() : validation;
    }

    public String getTips() {
        return tips;
    }

    public void setTips(String tips) {
        this.tips = tips == null ? "" : tips;
    }

    public KnowledgeGuidance getKnowledge() {
        return knowledge;
    }

    public void setKnowledge(KnowledgeGuidance knowledge) {
        this.knowledge = knowledge == null ? new KnowledgeGuidance() : knowledge;
    }
}
