package com.example.travel.controller;

import com.example.travel.agent.TravelPlannerAgentService;
import com.example.travel.exception.ResourceNotFoundException;
import com.example.travel.idempotency.IdempotencyService;
import com.example.travel.service.ConversationMemoryService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.QueryNormalizationService;
import com.example.travel.service.TripHistoryService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TravelControllerOwnershipTest {

    private TravelController controller(TravelPlannerAgentService planner, GraphProgressHub progress) {
        return new TravelController(
                planner,
                mock(ConversationMemoryService.class),
                progress,
                mock(TripHistoryService.class),
                mock(QueryNormalizationService.class),
                mock(IdempotencyService.class),
                mock(ObjectMapper.class));
    }

    @Test
    void eventStreamChecksOwnershipBeforeSubscribing() {
        TravelPlannerAgentService planner = mock(TravelPlannerAgentService.class);
        GraphProgressHub progress = mock(GraphProgressHub.class);
        TravelController controller = controller(planner, progress);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "unused");
        String foreignThread = "bob-" + java.util.UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Travel plan not found"))
                .when(planner).assertOwnedThread("alice", foreignThread);

        assertThrows(ResourceNotFoundException.class,
                () -> controller.planEvents(alice, foreignThread, false, -1));

        verify(planner).assertOwnedThread("alice", foreignThread);
        verifyNoInteractions(progress);
    }

    @Test
    void eventStreamUsesAuthenticatedPrincipalRatherThanRequestSuppliedIdentity() {
        TravelPlannerAgentService planner = mock(TravelPlannerAgentService.class);
        GraphProgressHub progress = mock(GraphProgressHub.class);
        TravelController controller = controller(planner, progress);
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "unused");
        String ownThread = "alice-" + java.util.UUID.randomUUID();

        controller.planEvents(alice, ownThread, true, -1);

        verify(planner).assertOwnedThread("alice", ownThread);
        verify(progress).subscribe(ownThread, true);
    }
}
