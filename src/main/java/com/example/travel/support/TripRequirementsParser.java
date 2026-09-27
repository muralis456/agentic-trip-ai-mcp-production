package com.example.travel.support;

import com.example.travel.model.TripRequirements;

import java.util.Locale;

public final class TripRequirementsParser {

    private TripRequirementsParser() {
    }

    public static TripRequirements parse(String request) {
        TripRequirements requirements = new TripRequirements();
        if (request == null || request.isBlank()) {
            return requirements;
        }
        String lower = request.toLowerCase(Locale.ROOT);
        requirements.setFamilyFriendly(containsAny(lower, "family", "kid", "children", "child-friendly"));
        requirements.setFoodExperiences(containsAny(lower, "food", "cuisine", "restaurant", "culinary", "dining"));
        requirements.setLocalExperiences(containsAny(lower, "local", "authentic", "culture", "cultural"));
        requirements.setBudgetConscious(containsAny(lower, "budget", "lakh", "₹", "under", "cheap", "affordable"));
        return requirements;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
