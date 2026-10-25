package com.example.travel.graph;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalEvaluationEvidenceTest {

    @Test
    void semanticSuccessCannotOverstateScoreWhenRequiredEvidenceIsMissing() {
        RoutedLlm llm = mock(RoutedLlm.class);
        when(llm.complete(eq(AgentRole.PLANNER), anyString(), anyString())).thenReturn("""
                {"status":"ACHIEVED","score":1.0,"reason":"Everything is complete.",
                 "satisfiedCriteria":[],"unmetCriteria":[],"blockingIssues":[],"recoverable":false}
                """);
        JsonSupport json = mock(JsonSupport.class);
        GoalEvaluation semantic = new GoalEvaluation();
        semantic.setStatus(GoalEvaluation.Status.ACHIEVED);
        semantic.setScore(1.0);
        when(json.read(anyString(), eq(GoalEvaluation.class))).thenReturn(Optional.of(semantic));
        AgentPlan plan = new AgentPlan();
        plan.setGoal("FLIGHT_SEARCH");
        plan.setTasks(List.of(new AgentTask("flights", "search flights", true)));
        plan.setSuccessCriteria(List.of("usable flight results"));
        TravelState state = new TravelState(Map.of(
                TravelState.AGENT_PLAN, plan,
                TravelState.FLIGHTS, List.of(),
                TravelState.HOTELS, List.of(),
                TravelState.RESEARCH, List.of(),
                TravelState.ATTRACTIONS, List.of()));

        GoalEvaluation evaluation = new GoalEvaluationService(llm, json).evaluate(state);

        verify(llm).complete(eq(AgentRole.PLANNER), anyString(), anyString());
        verify(json).read(anyString(), eq(GoalEvaluation.class));
        assertEquals(GoalEvaluation.Status.PARTIAL, evaluation.getStatus());
        assertEquals(0.0, evaluation.getScore());
        assertEquals(List.of("flights"), evaluation.getUnmetCriteria());
    }
}
