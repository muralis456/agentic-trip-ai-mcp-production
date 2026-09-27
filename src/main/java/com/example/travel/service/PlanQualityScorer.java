package com.example.travel.service;

import com.example.travel.graph.TravelState;
import com.example.travel.model.PlanQualityScore;
import com.example.travel.model.SemanticValidationResult;
import com.example.travel.model.TripRequirements;
import org.springframework.stereotype.Component;

@Component
public class PlanQualityScorer {

    private final RequirementEvaluator requirementEvaluator;

    public PlanQualityScorer(RequirementEvaluator requirementEvaluator) {
        this.requirementEvaluator = requirementEvaluator;
    }

    public PlanQualityScore score(TravelState state, SemanticValidationResult semantic) {
        TripRequirements requirements = state.tripRequirements();
        PlanQualityScore quality = new PlanQualityScore();
        if (state.budgetSummary() != null) {
            quality.setBudget(state.budgetSummary().isWithinBudget() ? 0.95 : 0.55);
        } else if (!state.needsBudget()) {
            quality.setBudget(1.0);
        } else {
            quality.setBudget(0.7);
        }

        if (!state.needsFlights()) {
            quality.setFlight(1.0);
        } else if (state.flights().isEmpty() || state.flights().stream()
                .allMatch(f -> "unavailable".equalsIgnoreCase(f.getStatus()))) {
            quality.setFlight(0.4);
        } else {
            boolean dateUnconfirmed = state.flights().stream()
                    .filter(f -> f != null && !"unavailable".equalsIgnoreCase(f.getStatus()))
                    .anyMatch(f -> f.getNotes() != null
                            && f.getNotes().toLowerCase(java.util.Locale.ROOT).contains("not independently confirmed"));
            quality.setFlight(dateUnconfirmed ? 0.60 : 0.85);
        }

        if (!state.needsHotels()) {
            quality.setHotel(1.0);
        } else if (state.hotels().isEmpty()) {
            quality.setHotel(0.5);
        } else {
            quality.setHotel(0.8);
        }

        if (!state.needsItinerary()) {
            quality.setItinerary(1.0);
        } else if (state.itinerary() == null || state.itinerary().isEmpty()) {
            quality.setItinerary(0.45);
        } else {
            quality.setItinerary(semantic == null || !semantic.failed() ? 0.9 : 0.65);
        }

        double preference = scorePreferences(state, requirements, semantic);
        quality.setPreferences(preference);

        if (!state.needsWeather() || state.weather() == null) {
            quality.setWeather(1.0);
        } else {
            // Rain is a destination condition, not a defect in the plan.
            // Score weather coverage/reliability rather than penalizing bad weather itself.
            quality.setWeather(0.90);
        }

        quality.recomputeWeighted(requirements);
        return quality;
    }

    private double scorePreferences(TravelState state,
                                      TripRequirements requirements,
                                      SemanticValidationResult semantic) {
        if (requirements != null && requirements.hasExplicitPreferences()) {
            double family = requirementEvaluator.familyScore(state, requirements);
            double food = requirementEvaluator.foodScore(state, requirements);
            double local = requirementEvaluator.localScore(state, requirements);
            int active = 0;
            double sum = 0.0;
            if (requirements.isFamilyFriendly()) {
                active++;
                sum += family;
            }
            if (requirements.isFoodExperiences()) {
                active++;
                sum += food;
            }
            if (requirements.isLocalExperiences()) {
                active++;
                sum += local;
            }
            if (active > 0) {
                return sum / active;
            }
        }
        if (semantic != null) {
            return Math.max(0.35, semantic.getScore());
        }
        return 0.85;
    }
}
