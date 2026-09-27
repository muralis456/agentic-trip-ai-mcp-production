package com.example.travel.graph;

import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.BudgetSummary;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.model.Itinerary;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalEvaluationScopeTest {

    @Test
    void itineraryGoalDoesNotRequireFlightsHotelsWeatherOrBudget() {
        AgentPlan plan = new AgentPlan();
        plan.setGoal("ITINERARY");
        plan.getTasks().add(new AgentTask("itinerary", "itinerary", true));
        plan.setSuccessCriteria(List.of("itinerary outcome"));

        Itinerary itinerary = new Itinerary();
        // Empty itinerary is not evidence, so use the task status to ensure the
        // evaluator is checking the goal scope rather than the complete-trip contract.
        plan.task("itinerary").setStatus(AgentTask.Status.SUCCEEDED);

        LinkedHashMap<String,Object> input = new LinkedHashMap<>();
        input.put(TravelState.AGENT_PLAN, plan);
        input.put(TravelState.REQUEST_TYPE, "ITINERARY");
        input.put(TravelState.ITINERARY, itinerary);
        input.put(TravelState.BUDGET, TravelState.UNSET_BUDGET);
        input.put(TravelState.BUDGET_SUMMARY, new BudgetSummary());
        input.put(TravelState.FLIGHTS, List.of());
        input.put(TravelState.HOTELS, List.of());
        input.put(TravelState.RESEARCH, List.of());
        input.put(TravelState.ATTRACTIONS, List.of());
        input.put(TravelState.WEATHER, new com.example.travel.model.WeatherForecast("", "", false));

        GoalEvaluation evaluation = new GoalEvaluationService(null, new JsonSupport(null)).evaluate(new TravelState(input));
        assertEquals(GoalEvaluation.Status.PARTIAL, evaluation.getStatus());
        // The only missing evidence is the itinerary itself; no full-trip criteria
        // such as flights/hotels/weather are injected.
        assertEquals(List.of("itinerary"), evaluation.getUnmetCriteria());
    }
}
