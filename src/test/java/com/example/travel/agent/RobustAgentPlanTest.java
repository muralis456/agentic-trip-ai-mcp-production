package com.example.travel.agent;

import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.IntentPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RobustAgentPlanTest {

    @Test
    void itineraryOnlyDoesNotExpandToFullTrip() {
        IntentPlan intent = new IntentPlan();
        intent.setRequestType("ITINERARY");
        intent.setNeedsItinerary(true);

        AgentPlan plan = AgentPlan.fromIntent(intent);

        assertTrue(plan.has("itinerary"));
        assertFalse(plan.has("flights"));
        assertFalse(plan.has("hotels"));
        assertFalse(plan.has("weather"));
        assertFalse(plan.has("budget"));
    }

    @Test
    void standaloneBudgetHasNoArtificialFlightHotelDependencies() {
        IntentPlan intent = new IntentPlan();
        intent.setRequestType("BUDGET");
        intent.setNeedsBudget(true);
        intent.setBudgetScope("TRIP");

        AgentPlan plan = AgentPlan.fromIntent(intent);
        AgentTask budget = plan.task("budget");

        assertNotNull(budget);
        assertTrue(budget.getDependsOn().isEmpty());
    }

    @Test
    void specialistPlanCannotAccidentallyBecomeCompleteTrip() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.hotelsOnly());

        assertEquals("HOTEL_SEARCH", plan.getGoal());
        assertEquals(1, plan.getTasks().size());
        assertTrue(plan.has("hotels"));
        assertFalse(plan.has("itinerary"));
    }
}
