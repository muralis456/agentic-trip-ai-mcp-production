package com.example.travel.tool;

import com.example.travel.model.FlightOption;
import com.example.travel.service.ExternalApiService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class FlightSearchTool {

    private final ExternalApiService externalApiService;

    public FlightSearchTool(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    public List<FlightOption> search(String originIata, String destinationIata, LocalDate departureDate) {
        return externalApiService.fetchFlights(
                originIata,
                destinationIata,
                departureDate == null ? null : departureDate.toString());
    }

    @Tool(description = "Search live flights with AviationStack using IATA codes. "
            + "Free plans do not support flight_date filtering; pass date only for trip context notes. "
            + "Pass only real IATA codes resolved from the airport database.")
    public String searchFlights(
            @ToolParam(description = "Departure IATA code, e.g. BLR") String originIata,
            @ToolParam(description = "Arrival IATA code, e.g. NRT") String destinationIata,
            @ToolParam(description = "Trip departure date yyyy-MM-dd (context only on free plans)", required = false) String departureDate) {
        LocalDate date = null;
        if (departureDate != null && !departureDate.isBlank()) {
            try {
                date = LocalDate.parse(departureDate.trim());
            } catch (Exception ignored) {
                // Invalid/missing dates must remain unknown. Never silently
                // substitute today's date because it can make live results look
                // like results for a user-requested travel date.
                date = null;
            }
        }
        List<FlightOption> flights = search(originIata, destinationIata, date);
        if (flights == null || flights.isEmpty()) {
            return "No flights found.";
        }
        return flights.stream().map(FlightOption::toDisplay).collect(Collectors.joining("\n"));
    }
}
