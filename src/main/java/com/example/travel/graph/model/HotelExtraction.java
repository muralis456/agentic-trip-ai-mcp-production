package com.example.travel.graph.model;

import com.example.travel.model.HotelOption;

import java.util.ArrayList;
import java.util.List;

public class HotelExtraction {

    private List<HotelOption> hotels = new ArrayList<>();

    public List<HotelOption> getHotels() {
        return hotels;
    }

    public void setHotels(List<HotelOption> hotels) {
        this.hotels = hotels == null ? new ArrayList<>() : hotels;
    }
}
