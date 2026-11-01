package com.example.travel.graph.node;

import com.example.travel.agent.FinalPlannerAgentService;
import com.example.travel.dto.TravelRequest;
import com.example.travel.graph.TravelState;
import com.example.travel.jev.JevHitlDecisionService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class FinalizationNodeTest {

    @Test
    void completedTripAlwaysPausesForHumanApproval() {
        FinalPlannerAgentService planner = mock(FinalPlannerAgentService.class);
        JevHitlDecisionService jev = mock(JevHitlDecisionService.class);
        TravelRequest request = new TravelRequest();
        request.setPrompt("Plan a complete trip to Tokyo");
        Map<String, Object> input = TravelState.fromRequest(request, "");
        input.put(TravelState.REQUEST_TYPE, "TRIP_PLANNING");
        TravelState state = new TravelState(input);

        Map<String, Object> updates = new FinalizationNode(planner, Optional.of(jev)).apply(state);

        assertEquals(Boolean.TRUE, updates.get(TravelState.AWAITING_APPROVAL));
        verifyNoInteractions(jev);
        verify(planner).buildTips(state);
    }

    @Test
    void informationalRequestDoesNotRequireTripApproval() {
        FinalPlannerAgentService planner = mock(FinalPlannerAgentService.class);
        JevHitlDecisionService jev = mock(JevHitlDecisionService.class);
        when(jev.decide(any())).thenReturn(
                new JevHitlDecisionService.Decision("AUTO_COMPLETE", 0.84, true, "test"));
        TravelRequest request = new TravelRequest();
        request.setPrompt("What is the weather in Tokyo?");
        Map<String, Object> input = TravelState.fromRequest(request, "");
        input.put(TravelState.REQUEST_TYPE, "WEATHER");
        TravelState state = new TravelState(input);

        Map<String, Object> updates = new FinalizationNode(planner, Optional.of(jev)).apply(state);

        assertEquals(Boolean.FALSE, updates.get(TravelState.AWAITING_APPROVAL));
        verify(jev).decide(state);
        verifyNoInteractions(planner);
    }
}
