package com.example.travel.agent;

import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.IntentPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlanTest {
    @Test
    void fullTripCreatesCompleteDashboardTaskContract() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.fullTrip());

        assertEquals("TRIP_PLANNING", plan.getGoal());
        assertFalse(plan.isSelectiveExecution());
        assertEquals(List.of("flight", "hotel", "research", "weather"), plan.executablePreSupervisorAgents());
        assertTrue(plan.has("flights"));
        assertTrue(plan.has("hotels"));
        assertTrue(plan.has("research"));
        assertTrue(plan.has("weather"));
        assertTrue(plan.has("budget"));
        assertTrue(plan.has("itinerary"));
        assertTrue(plan.ready("flights"));
        assertFalse(plan.ready("itinerary"));
    }

    @Test
    void specialistRequestRemainsSelectiveByContentButExecutableOnInitialPass() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.hotelsOnly());
        assertFalse(plan.isSelectiveExecution());
        assertEquals(List.of("hotel"), plan.executablePreSupervisorAgents());
        assertTrue(plan.has("hotels"));
        assertFalse(plan.has("flights"));
        assertFalse(plan.has("weather"));
    }

    @Test
    void selectiveRecoveryCannotFallBackToStaleTasks() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.fullTrip());
        plan.selectForExecution(List.of("hotels"));
        assertTrue(plan.isSelectiveExecution());
        assertEquals(List.of("hotel"), plan.executablePreSupervisorAgents());
        assertEquals(AgentTask.Status.SKIPPED, plan.task("flights").getStatus());
    }

    @Test
    void failedRequiredTaskIsRetryableOnlyBeforeAttemptLimit() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.weatherOnly());
        plan.markStarted("weather");
        plan.markFailed("weather", "provider timeout");
        assertTrue(plan.hasRetryableFailure(2));
        plan.markStarted("weather");
        plan.markFailed("weather", "provider timeout");
        assertFalse(plan.hasRetryableFailure(2));
    }

    @Test
    void selectiveRecoveryPreservesDownstreamTasks() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.fullTrip());
        plan.selectForExecution(List.of("hotels"));

        assertTrue(plan.shouldExecute("hotels"));
        assertFalse(plan.shouldExecute("flights"));
        assertFalse(plan.shouldExecute("budget"));
        assertFalse(plan.shouldExecute("itinerary"));
    }

    @Test
    void initialPlanExecutesPendingTasksFromCanonicalPlan() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.fullTrip());
        assertTrue(plan.shouldExecute("flights"));
        assertTrue(plan.shouldExecute("hotels"));
        assertTrue(plan.shouldExecute("weather"));
        assertFalse(plan.shouldExecute("budget"));
        assertFalse(plan.shouldExecute("itinerary"));
    }

    @Test
    void recoveryDependencyClosureIsTransitive() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.fullTrip());
        plan.selectForExecution(List.of("flights"));

        assertTrue(plan.shouldExecute("flights"));
        assertFalse(plan.shouldExecute("budget"));
        assertFalse(plan.shouldExecute("itinerary"));
        assertFalse(plan.shouldExecute("hotels"));
        assertFalse(plan.shouldExecute("weather"));
    }

    @Test
    void failedTaskIsNotImmediatelyReadyForAccidentalSamePassRetry() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.weatherOnly());
        plan.markStarted("weather");
        plan.markFailed("weather", "provider timeout");

        assertFalse(plan.ready("weather"));
        assertEquals(1, plan.task("weather").getAttempts());
    }

    @Test
    void selectiveRecoveryKeepsSuccessfulPrerequisitesSatisfied() {
        AgentPlan plan = AgentPlan.fromIntent(IntentPlan.fullTrip());
        plan.markSucceeded("flights");
        plan.markSucceeded("hotels");
        plan.selectForExecution(List.of("hotels"));

        assertEquals(AgentTask.Status.SUCCEEDED, plan.task("flights").getStatus());
        assertEquals(AgentTask.Status.READY, plan.task("hotels").getStatus());
        assertEquals(AgentTask.Status.PENDING, plan.task("budget").getStatus());
        // The hotel is being re-executed, so budget must wait for the new hotel
        // result rather than using stale hotel data.
        assertFalse(plan.ready("budget"));
        plan.markStarted("hotels");
        plan.markSucceeded("hotels");
        assertTrue(plan.ready("budget"));
    }

    @Test
    void unknownDependencyCannotBecomeReady() {
        AgentPlan plan = new AgentPlan();
        AgentTask task = new AgentTask("budget", "budget", true, "missing");
        plan.setTasks(List.of(task));
        assertFalse(plan.ready("budget"));
    }

}
