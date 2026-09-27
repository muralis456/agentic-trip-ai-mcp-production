package com.example.travel.agent;

import com.example.travel.graph.TravelState;
import com.example.travel.model.WeatherForecast;
import com.example.travel.support.TripSlotHeuristics;
import com.example.travel.tool.WeatherTool;
import org.springframework.stereotype.Service;

@Service
public class WeatherAgentService {

    private final WeatherTool weatherTool;

    public WeatherAgentService(WeatherTool weatherTool) {
        this.weatherTool = weatherTool;
    }

    public WeatherForecast forecast(TravelState state) {
        if (state == null || !state.needsWeather()) {
            return new WeatherForecast("", "", false);
        }

        // The current request is the authoritative source for a specialist
        // lookup. If an upstream planner ever misses the destination, recover
        // it from the same prompt before giving up. This keeps weather queries
        // independent from generic RAG knowledge classification.
        String destination = TravelState.firstNonBlank(
                state.destination(),
                TripSlotHeuristics.extractDestinationHint(state.userRequest()));

        if (TravelState.isBlank(destination)) {
            return new WeatherForecast("", "Weather location is required.", false);
        }

        return weatherTool.forecast(destination, state.departureDate(), state.returnDate(), state.userRequest());
    }
}
