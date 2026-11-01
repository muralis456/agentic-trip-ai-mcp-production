package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.dto.TravelRequest;
import com.example.travel.graph.TravelState;
import com.example.travel.model.MemoryIntentDecision;
import com.example.travel.dto.TripPlanResult;
import com.example.travel.model.WeatherForecast;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IntentContextDisambiguationTest {

    @Test
    void confidentMemoryRejectionKeepsNewWeatherRequestAndDropsStaleHistoryIntent() {
        RoutedLlm llm = mock(RoutedLlm.class);
        SemanticMemoryArbiter memoryArbiter = mock(SemanticMemoryArbiter.class);
        when(llm.complete(eq(AgentRole.EXTRACT), anyString(), anyString()))
                .thenReturn("""
                        {"requestType":"MULTI_CAPABILITY","needsWeather":true,
                         "needsHistory":true,"confidence":0.95}
                        """)
                .thenReturn("""
                        {"needsHistory":false,"historyOnly":false,"confidence":0.96}
                        """);
        when(memoryArbiter.recover("what is the weather in Bangalore"))
                .thenReturn(new MemoryIntentDecision(false, false, 0.5));

        TravelRequest request = new TravelRequest();
        request.setPrompt("what is the weather in Bangalore");
        TravelState state = new TravelState(TravelState.fromRequest(
                request, "user: show my recent Tokyo trip\nassistant: Here are your trip details."));
        IntentAgentService service = new IntentAgentService(
                llm, new JsonSupport(new ObjectMapper()), memoryArbiter, mock(GraphProgressHub.class));

        var updates = service.classify(state);

        assertEquals(true, updates.get(TravelState.NEEDS_WEATHER));
        assertEquals(false, updates.get(TravelState.NEEDS_HISTORY));
        assertEquals(false, updates.get(TravelState.NEEDS_FLIGHTS));

        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(eq(AgentRole.EXTRACT), systemPrompts.capture(), userPrompts.capture());
        assertEquals(true, systemPrompts.getAllValues().get(1).contains("CURRENT-TURN AUTHORITY"));
        assertEquals(true, userPrompts.getAllValues().get(1).contains("show my recent Tokyo trip"));
    }

    @Test
    void explicitRecentTripRetrievalStillUsesHistoryCapability() {
        RoutedLlm llm = mock(RoutedLlm.class);
        SemanticMemoryArbiter memoryArbiter = mock(SemanticMemoryArbiter.class);
        when(llm.complete(eq(AgentRole.EXTRACT), anyString(), anyString()))
                .thenReturn("""
                        {"requestType":"HISTORY","needsHistory":true,"confidence":0.95}
                        """)
                .thenReturn("""
                        {"needsHistory":true,"historyOnly":true,"confidence":0.96}
                        """);

        TravelRequest request = new TravelRequest();
        request.setPrompt("show me my recent trip");
        TravelState state = new TravelState(TravelState.fromRequest(request, "previous conversation"));
        IntentAgentService service = new IntentAgentService(
                llm, new JsonSupport(new ObjectMapper()), memoryArbiter, mock(GraphProgressHub.class));

        var updates = service.classify(state);

        assertEquals(true, updates.get(TravelState.NEEDS_HISTORY));
        assertEquals(false, updates.get(TravelState.NEEDS_WEATHER));
        verifyNoInteractions(memoryArbiter);
    }

    @Test
    void additiveHotelFollowUpKeepsThePreviousWeatherCapability() {
        RoutedLlm llm = mock(RoutedLlm.class);
        SemanticMemoryArbiter memoryArbiter = mock(SemanticMemoryArbiter.class);
        when(llm.complete(eq(AgentRole.EXTRACT), anyString(), anyString()))
                .thenReturn("""
                        {"requestType":"HOTEL_SEARCH","needsHotels":true,
                         "needsHistory":false,"confidence":0.95}
                        """);

        TravelRequest request = new TravelRequest();
        request.setPrompt("give me best hotels as well");
        request.setDestination("Bengaluru");
        TripPlanResult memoryPlan = new TripPlanResult();
        memoryPlan.getTrip().setDestination("Bengaluru");
        WeatherForecast cachedWeather = new WeatherForecast("Bengaluru", "Clear skies", false);
        cachedWeather.getCurrent().setTemperature(24.0);
        memoryPlan.setWeather(cachedWeather);
        request.setMemoryPlan(memoryPlan);
        TravelState state = new TravelState(TravelState.fromRequest(
                request, "user: what is the weather in Bangalore\nassistant: Weather details for Bengaluru."));
        IntentAgentService service = new IntentAgentService(
                llm, new JsonSupport(new ObjectMapper()), memoryArbiter, mock(GraphProgressHub.class));

        var updates = service.classify(state);

        assertEquals(true, updates.get(TravelState.NEEDS_WEATHER));
        assertEquals(true, updates.get(TravelState.NEEDS_HOTELS));
        assertEquals(false, updates.get(TravelState.NEEDS_HISTORY));
        assertEquals(java.util.List.of("weather"), updates.get(TravelState.REUSE_CAPABILITIES_FROM_MEMORY));
        assertEquals(true, state.weather().hasWeatherData());
        verifyNoInteractions(memoryArbiter);

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(eq(AgentRole.EXTRACT), systemPrompt.capture(), userPrompt.capture());
        assertEquals(true, systemPrompt.getValue().contains("ADDITIVE FOLLOW-UPS"));
        assertEquals(true, userPrompt.getValue().contains("current conversation may resolve follow-ups"));
        assertEquals(true, userPrompt.getValue().contains("what is the weather in Bangalore"));
        assertEquals(true, systemPrompt.getValue().contains("reuseCapabilitiesFromMemory"));
    }
}
