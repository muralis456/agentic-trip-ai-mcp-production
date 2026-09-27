package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Parsed once from the user request; validators score against structured itinerary data.
 */
public class TripRequirements implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean familyFriendly;
    private boolean foodExperiences;
    private boolean localExperiences;
    private boolean budgetConscious;

    public boolean isFamilyFriendly() {
        return familyFriendly;
    }

    public void setFamilyFriendly(boolean familyFriendly) {
        this.familyFriendly = familyFriendly;
    }

    public boolean isFoodExperiences() {
        return foodExperiences;
    }

    public void setFoodExperiences(boolean foodExperiences) {
        this.foodExperiences = foodExperiences;
    }

    public boolean isLocalExperiences() {
        return localExperiences;
    }

    public void setLocalExperiences(boolean localExperiences) {
        this.localExperiences = localExperiences;
    }

    public boolean isBudgetConscious() {
        return budgetConscious;
    }

    public void setBudgetConscious(boolean budgetConscious) {
        this.budgetConscious = budgetConscious;
    }

    public boolean hasExplicitPreferences() {
        return familyFriendly || foodExperiences || localExperiences;
    }
}
