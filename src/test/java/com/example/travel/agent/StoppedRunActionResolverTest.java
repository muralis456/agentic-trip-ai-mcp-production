package com.example.travel.agent;

import com.example.travel.graph.TravelState;
import com.example.travel.model.StoppedRunActionDecision;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoppedRunActionResolverTest {

    @Test
    void continueTurnUsesSavedRequirementWithoutDependingOnBrowserSession() {
        RoutedLlm routedLlm = mock(RoutedLlm.class);
        StoppedRunActionResolver resolver = new StoppedRunActionResolver(
                routedLlm, new JsonSupport(new ObjectMapper()));
        String previousRequest = "From Bengaluru to Tokyo for 7 days under ₹2 lakh.";
        TravelState checkpoint = new TravelState(Map.of(
                TravelState.USER_REQUEST, previousRequest,
                TravelState.ORIGIN, "Bengaluru",
                TravelState.DESTINATION, "Tokyo"
        ));

        StoppedRunActionDecision decision = resolver.resolve(checkpoint, "Continue");

        assertEquals("RESUME", decision.getAction());
        assertEquals(previousRequest, decision.getEffectiveRequest());
        verifyNoInteractions(routedLlm);
    }

    @Test
    void explicitModificationPromptMustReframePreviousRequestAndCurrentTurn() {
        RoutedLlm routedLlm = mock(RoutedLlm.class);
        JsonSupport jsonSupport = new JsonSupport(new ObjectMapper());
        StoppedRunActionResolver resolver = new StoppedRunActionResolver(routedLlm, jsonSupport);

        TravelState checkpoint = new TravelState(Map.of(
                TravelState.USER_REQUEST,
                "From Bengaluru to Tokyo for 7 days under ₹2 lakh family-friendly with good food.",
                TravelState.ORIGIN, "Bengaluru",
                TravelState.DESTINATION, "Tokyo",
                TravelState.DEPARTURE_DATE, "2026-09-26",
                TravelState.RETURN_DATE, "2026-10-02",
                TravelState.BUDGET_LABEL, "₹2 lakh",
                TravelState.TRAVEL_STYLE, "family-friendly"
        ));

        when(routedLlm.complete(any(), anyString(), anyString(), any())).thenReturn("" +
                "{\n" +
                "  \"action\": \"RESUME\",\n" +
                "  \"confidence\": 0.9,\n" +
                "  \"reason\": \"The user is simply continuing the same trip.\",\n" +
                "  \"effectiveRequest\": \"From Bengaluru to Tokyo for 7 days under ₹2 lakh family-friendly with good food.\"\n" +
                "}\n");

        StoppedRunActionDecision decision = resolver.resolve(checkpoint, "Actually make it 5 days and prefer direct flights.");

        assertEquals("MODIFY", decision.getAction());
        assertTrue(decision.getEffectiveRequest().toLowerCase().contains("5 days"));
        assertTrue(decision.getEffectiveRequest().toLowerCase().contains("direct flights"));
        assertTrue(decision.getEffectiveRequest().toLowerCase().contains("tokyo"));
    }

    @Test
    void totallyDifferentTripRequestMustBeClassifiedAsNewRequest() {
        RoutedLlm routedLlm = mock(RoutedLlm.class);
        JsonSupport jsonSupport = new JsonSupport(new ObjectMapper());
        StoppedRunActionResolver resolver = new StoppedRunActionResolver(routedLlm, jsonSupport);

        TravelState checkpoint = new TravelState(Map.of(
                TravelState.USER_REQUEST,
                "From Bengaluru to Tokyo for 7 days under ₹2 lakh family-friendly with good food.",
                TravelState.ORIGIN, "Bengaluru",
                TravelState.DESTINATION, "Tokyo",
                TravelState.BUDGET_LABEL, "₹2 lakh"
        ));

        StoppedRunActionDecision decision = resolver.resolve(checkpoint, "Plan a completely different trip to Paris for 4 days with a boutique hotel budget.");

        assertEquals("NEW_REQUEST", decision.getAction());
        assertTrue(decision.getEffectiveRequest().toLowerCase().contains("paris"));
        assertFalse(decision.getEffectiveRequest().toLowerCase().contains("tokyo"));
    }
}
