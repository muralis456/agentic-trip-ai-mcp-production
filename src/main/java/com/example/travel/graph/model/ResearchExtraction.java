package com.example.travel.graph.model;

import com.example.travel.model.TravelAttraction;
import com.example.travel.model.TravelResearch;

import java.util.ArrayList;
import java.util.List;

public class ResearchExtraction {

    private List<TravelResearch> research = new ArrayList<>();
    private List<TravelAttraction> attractions = new ArrayList<>();

    public List<TravelResearch> getResearch() {
        return research;
    }

    public void setResearch(List<TravelResearch> research) {
        this.research = research == null ? new ArrayList<>() : research;
    }

    public List<TravelAttraction> getAttractions() {
        return attractions;
    }

    public void setAttractions(List<TravelAttraction> attractions) {
        this.attractions = attractions == null ? new ArrayList<>() : attractions;
    }
}
