package com.example.travel.agent;

import com.example.travel.model.IntentPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentBudgetScopeTest {

    @Test
    void fullTripUsesTripBudgetScope() {
        IntentPlan plan = IntentPlan.fullTrip();
        assertTrue(plan.isNeedsBudget());
        assertEquals("TRIP", plan.getBudgetScope());
    }

    @Test
    void newIntentDefaultsToNoBudgetScope() {
        IntentPlan plan = new IntentPlan();
        assertFalse(plan.isNeedsBudget());
        assertEquals("NONE", plan.getBudgetScope());
    }
}
