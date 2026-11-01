package com.example.travel.graph;

import com.example.travel.dto.TravelRequest;
import com.example.travel.model.AgentTask;
import com.example.travel.model.PlanExtraction;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionPlanningServiceTest {

    @Test
    void fullTripAddsOptionalKnowledgeTaskWhenPlannerOmitsIt() {
        RoutedLlm llm = mock(RoutedLlm.class);
        JsonSupport json = mock(JsonSupport.class);
        PlanExtraction extraction = new PlanExtraction();
        PlanExtraction.PlannedTask itinerary = new PlanExtraction.PlannedTask();
        itinerary.setId("itinerary");
        itinerary.setAgent("itinerary");
        extraction.setTasks(List.of(itinerary));
        when(llm.complete(any(), anyString(), anyString())).thenReturn("{}");
        when(json.read(anyString(), eq(PlanExtraction.class))).thenReturn(Optional.of(extraction));

        TravelRequest request = new TravelRequest();
        request.setPrompt("Plan a complete trip to Tokyo");
        Map<String, Object> input = TravelState.fromRequest(request, "");
        input.put(TravelState.REQUEST_TYPE, "TRIP_PLANNING");
        TravelState state = new TravelState(input);
        ProductionPlanningService planner = new ProductionPlanningService(
                llm, json, mock(GraphProgressHub.class));

        AgentTask knowledge = planner.createPlan(state).task("knowledge");

        assertNotNull(knowledge);
        assertFalse(knowledge.isRequired());
    }
}
