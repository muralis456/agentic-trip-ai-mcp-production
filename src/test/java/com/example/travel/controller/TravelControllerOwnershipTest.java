package com.example.travel.controller;

import com.example.travel.agent.TravelPlannerAgentService;
import com.example.travel.dto.TravelPlanResponse;
import com.example.travel.dto.TravelRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TravelControllerOwnershipTest {

    private TravelController controller(TravelPlannerAgentService planner, GraphProgressHub progress,
                                        TripHistoryService trips) {
        return new TravelController(
                planner,
                mock(ConversationMemoryService.class),
                progress,
                trips,
                mock(QueryNormalizationService.class),
                mock(IdempotencyService.class),
                mock(ObjectMapper.class));
    }

    @Test
    void eventStreamChecksOwnershipBeforeSubscribing() {
        TravelPlannerAgentService planner = mock(TravelPlannerAgentService.class);
        GraphProgressHub progress = mock(GraphProgressHub.class);
        TravelController controller = controller(planner, progress, mock(TripHistoryService.class));
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
        TravelController controller = controller(planner, progress, mock(TripHistoryService.class));
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "unused");
        String ownThread = "alice-" + java.util.UUID.randomUUID();

        controller.planEvents(alice, ownThread, true, -1);

        verify(planner).assertOwnedThread("alice", ownThread);
        verify(progress).subscribe(ownThread, true);
    }

    @Test
    void tripHistoryIsAlwaysScopedToAuthenticatedPrincipal() {
        TravelHistoryFixture fixture = new TravelHistoryFixture();
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "unused");

        fixture.controller.trips(alice, 25);

        verify(fixture.trips).list("alice", 25);
        verify(fixture.trips, never()).list(eq("bob"), anyInt());
    }

    @Test
    void tripDetailLookupUsesAuthenticatedPrincipalAndCannotSelectAnotherUsersRecord() {
        TravelHistoryFixture fixture = new TravelHistoryFixture();
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "unused");

        fixture.controller.trip(alice, 42L);

        verify(fixture.trips).getPlan("alice", 42L);
        verify(fixture.trips, never()).getPlan(eq("bob"), anyLong());
    }

    @Test
    void createPlanOverwritesForgedRequestIdentityWithAuthenticatedPrincipal() {
        TravelPlannerAgentService planner = mock(TravelPlannerAgentService.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        TripHistoryService trips = mock(TripHistoryService.class);
        QueryNormalizationService normalization = mock(QueryNormalizationService.class);
        TravelController controller = new TravelController(planner, memory, mock(GraphProgressHub.class),
                trips, normalization, mock(IdempotencyService.class), mock(ObjectMapper.class));
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "unused");
        TravelRequest request = new TravelRequest();
        request.setUserId("bob");
        request.setPrompt("Plan a trip to Tokyo");
        TravelPlanResponse response = new TravelPlanResponse();
        when(normalization.normalize(any())).thenAnswer(invocation -> {
            TravelRequest normalized = invocation.getArgument(0);
            return new QueryNormalizationService.NormalizationResult(
                    normalized.getPrompt(), normalized.getPrompt(), java.util.List.of(), java.util.List.of());
        });
        when(planner.createTravelPlan(any(), any())).thenReturn(response);

        controller.createPlan(alice, request);

        org.mockito.ArgumentCaptor<TravelRequest> captured = org.mockito.ArgumentCaptor.forClass(TravelRequest.class);
        verify(planner).createTravelPlan(captured.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals("alice", captured.getValue().getUserId());
    }

    private static final class TravelHistoryFixture {
        final TravelPlannerAgentService planner = mock(TravelPlannerAgentService.class);
        final GraphProgressHub progress = mock(GraphProgressHub.class);
        final TripHistoryService trips = mock(TripHistoryService.class);
        final TravelController controller = new TravelController(
                planner, mock(ConversationMemoryService.class), progress, trips,
                mock(QueryNormalizationService.class), mock(IdempotencyService.class), mock(ObjectMapper.class));
    }
}
