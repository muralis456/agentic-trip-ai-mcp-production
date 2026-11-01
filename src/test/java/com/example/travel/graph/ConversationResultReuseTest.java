package com.example.travel.graph;

import com.example.travel.agent.BudgetAgentService;
import com.example.travel.agent.FlightAgentService;
import com.example.travel.agent.HotelAgentService;
import com.example.travel.agent.ItineraryAgentService;
import com.example.travel.agent.TravelResearchAgentService;
import com.example.travel.agent.WeatherAgentService;
import com.example.travel.dto.TravelRequest;
import com.example.travel.dto.TripPlanResult;
import com.example.travel.graph.node.HistoryNode;
import com.example.travel.graph.node.RagNode;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentTask;
import com.example.travel.model.HotelOption;
import com.example.travel.model.WeatherForecast;
import com.example.travel.service.AgentRunControlService;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.tool.AirportLookupTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ConversationResultReuseTest {

    @Test
    void reusesCachedWeatherWithoutCallingWeatherAgent() {
        WeatherForecast weatherResult = new WeatherForecast("Bengaluru", "Clear skies", false);
        weatherResult.getCurrent().setTemperature(24.0);
        TripPlanResult memory = cachedPlan();
        memory.setWeather(weatherResult);

        WeatherAgentService weather = mock(WeatherAgentService.class);
        Map<String, Object> updates = executeCached("weather", memory, weather, mock(HotelAgentService.class));

        assertSame(weatherResult, updates.get(TravelState.WEATHER));
        verifyNoInteractions(weather);
    }

    @Test
    void reusesCachedHotelsWithoutCallingHotelAgent() {
        HotelOption hotelResult =
                new HotelOption("Central Hotel", "MG Road", "₹5,000", "Central location");
        TripPlanResult memory = cachedPlan();
        memory.setHotels(List.of(hotelResult));

        HotelAgentService hotel = mock(HotelAgentService.class);
        Map<String, Object> updates = executeCached("hotels", memory, mock(WeatherAgentService.class), hotel);

        assertEquals(List.of(hotelResult), updates.get(TravelState.HOTELS));
        verifyNoInteractions(hotel);
    }

    private TripPlanResult cachedPlan() {
        TripPlanResult result = new TripPlanResult();
        result.getTrip().setDestination("Bengaluru");
        return result;
    }

    private Map<String, Object> executeCached(
            String capability, TripPlanResult memory, WeatherAgentService weather, HotelAgentService hotel) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProductionExecutionNode node = new ProductionExecutionNode(
                    mock(FlightAgentService.class), hotel, mock(TravelResearchAgentService.class),
                    weather, mock(BudgetAgentService.class), mock(ItineraryAgentService.class),
                    mock(RagNode.class), mock(HistoryNode.class), mock(AirportLookupTool.class),
                    executor, mock(GraphProgressHub.class), mock(AgentRunControlService.class));

            TravelRequest request = new TravelRequest();
            request.setDestination("Bengaluru");
            request.setMemoryPlan(memory);
            Map<String, Object> stateData = TravelState.fromRequest(request, "");
            stateData.put(TravelState.REUSE_CAPABILITIES_FROM_MEMORY, List.of(capability));
            AgentPlan plan = new AgentPlan();
            plan.setTasks(List.of(new AgentTask(capability, capability, true)));
            stateData.put(TravelState.AGENT_PLAN, plan);

            return node.apply(new TravelState(stateData));
        } finally {
            executor.shutdownNow();
        }
    }
}
