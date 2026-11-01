package com.example.travel.agent;

import com.example.travel.dto.TravelRequest;
import com.example.travel.graph.TravelState;
import com.example.travel.model.WeatherForecast;
import com.example.travel.dto.TripPlanResult;
import com.example.travel.tool.WeatherTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WeatherAgentServiceTest {

    @Test
    void additiveFollowUpUsesCachedWeatherWithoutCallingWeatherTool() {
        WeatherTool weatherTool = mock(WeatherTool.class);
        WeatherAgentService service = new WeatherAgentService(weatherTool);
        WeatherForecast cachedWeather = new WeatherForecast("Bengaluru", "Clear skies", false);
        cachedWeather.getCurrent().setTemperature(24.0);

        TravelRequest request = new TravelRequest();
        request.setPrompt("give me best hotels as well");
        request.setDestination("Bangalore");
        TripPlanResult memoryPlan = new TripPlanResult();
        memoryPlan.getTrip().setDestination("Bengaluru");
        memoryPlan.setWeather(cachedWeather);
        request.setMemoryPlan(memoryPlan);
        var stateData = TravelState.fromRequest(request, "");
        stateData.put(TravelState.NEEDS_WEATHER, true);
        stateData.put(TravelState.REUSE_CAPABILITIES_FROM_MEMORY, java.util.List.of("weather"));
        TravelState state = new TravelState(stateData);

        assertSame(cachedWeather, service.forecast(state));
        verifyNoInteractions(weatherTool);
    }
}
