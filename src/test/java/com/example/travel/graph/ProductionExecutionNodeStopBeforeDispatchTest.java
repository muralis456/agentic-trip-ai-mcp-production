package com.example.travel.graph;

import com.example.travel.agent.*;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.graph.node.HistoryNode;
import com.example.travel.graph.node.RagNode;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.tool.AirportLookupTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductionExecutionNodeStopBeforeDispatchTest {

    @Test
    void doesNotStartAnyTaskWhenDurableStopIsAlreadyRequested() {
        FlightAgentService flight = mock(FlightAgentService.class);
        HotelAgentService hotel = mock(HotelAgentService.class);
        TravelResearchAgentService research = mock(TravelResearchAgentService.class);
        WeatherAgentService weather = mock(WeatherAgentService.class);
        BudgetAgentService budget = mock(BudgetAgentService.class);
        ItineraryAgentService itinerary = mock(ItineraryAgentService.class);
        AgentRunControlService control = mock(AgentRunControlService.class);
        when(control.isStopRequested("alice-" + "thread")).thenReturn(true);

        ProductionExecutionNode node = new ProductionExecutionNode(
                flight, hotel, research, weather, budget, itinerary,
                mock(RagNode.class), mock(HistoryNode.class), mock(AirportLookupTool.class),
                mock(ExecutorService.class), mock(GraphProgressHub.class), control);
        AgentPlan plan = new AgentPlan();
        plan.setTasks(List.of(new AgentTask("flights", "search flights", true)));
        TravelState state = new TravelState(Map.of(
                TravelState.AGENT_PLAN, plan,
                TravelState.GRAPH_THREAD_ID, "alice-thread"));

        assertThrows(GraphStopRequestedException.class, () -> node.apply(state));

        assertEqualsTaskNotStarted(plan);
        verifyNoInteractions(flight, hotel, research, weather, budget, itinerary);
    }

    private void assertEqualsTaskNotStarted(AgentPlan plan) {
        org.junit.jupiter.api.Assertions.assertEquals(
                AgentTask.Status.PENDING, plan.task("flights").getStatus());
    }
}
