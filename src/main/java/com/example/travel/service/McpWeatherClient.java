package com.example.travel.service;

import com.example.travel.model.WeatherForecast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpWeatherClient {

    private static final Logger log = LoggerFactory.getLogger(McpWeatherClient.class);

    private final McpToolClient client;

    public McpWeatherClient(McpToolClient client) {
        this.client = client;
    }

    public WeatherForecast forecast(String destination, LocalDate start, LocalDate end) {
        return forecast(destination, start, end, "Weather forecast for " + destination);
    }

    public WeatherForecast forecast(String destination, LocalDate start, LocalDate end, String userInput) {
        try {
            JsonNode root = client.invokePreferred("get_weather", "Weather forecast for a travel destination", userInput, Map.of(
                    "destination", destination == null ? "" : destination,
                    "startDate", start == null ? "" : start.toString(),
                    "endDate", end == null ? "" : end.toString()));

            boolean success = root.path("success").asBoolean(true);
            String providerMessage = root.path("message").asText("");
            if (providerMessage.isBlank()) {
                providerMessage = root.path("summary").asText("");
            }
            if (!success) {
                String reason = providerMessage.isBlank() ? "OpenWeather MCP returned an unsuccessful weather response." : providerMessage;
                log.warn("mcp.client.weather-provider-failure destination={} start={} end={} reason={}",
                        destination, start, end, reason);
                return new WeatherForecast(destination, reason, false);
            }

            WeatherForecast result = new WeatherForecast(root.path("location").asText(destination),
                    root.path("summary").asText("Weather unavailable."),
                    root.path("rainLikely").asBoolean(false));
            JsonNode current = root.path("current");
            if (current.isObject()) {
                WeatherForecast.CurrentWeather value = new WeatherForecast.CurrentWeather();
                value.setTemperature(number(current, "temperature"));
                value.setFeelsLike(number(current, "feelsLike"));
                value.setHumidity(integer(current, "humidity"));
                value.setPressure(integer(current, "pressure"));
                value.setDewPoint(number(current, "dewPoint"));
                value.setUvIndex(number(current, "uvIndex"));
                value.setClouds(integer(current, "clouds"));
                value.setVisibilityMeters(integer(current, "visibilityMeters"));
                value.setWindSpeed(number(current, "windSpeed"));
                value.setWindGust(number(current, "windGust"));
                value.setWindDeg(integer(current, "windDeg"));
                value.setRain1h(number(current, "rain1h"));
                value.setSnow1h(number(current, "snow1h"));
                value.setObservedAt(longValue(current, "observedAt"));
                value.setSunrise(longValue(current, "sunrise"));
                value.setSunset(longValue(current, "sunset"));
                value.setTimezone(current.path("timezone").asText(""));
                value.setCondition(current.path("condition").asText(""));
                value.setDescription(current.path("description").asText(""));
                value.setIcon(current.path("icon").asText(""));
                result.setCurrent(value);
            }
            JsonNode days = root.path("days");
            if (!days.isArray() && root.path("daily").isObject()) {
                days = root.path("daily").path("days");
            }
            if (days.isArray()) {
                java.util.List<WeatherForecast.DailyForecast> forecasts = new java.util.ArrayList<>();
                for (JsonNode day : days) {
                    forecasts.add(new WeatherForecast.DailyForecast(
                            day.path("date").asText(day.path("time").asText("")),
                            day.path("condition").asText(""),
                            day.path("icon").asText("🌤️"),
                            day.path("high").isNumber() ? day.path("high").doubleValue() : null,
                            day.path("low").isNumber() ? day.path("low").doubleValue() : null,
                            day.path("rainProbability").isNumber() ? day.path("rainProbability").intValue() : null));
                }
                result.setDays(forecasts);
            }
            return result;
        } catch (Exception exception) {
            log.error("mcp.client.error client=McpWeatherClient operation=forecast destination={} start={} end={} errorType={} errorMessage={}",
                    destination, start, end, exception.getClass().getName(), safeMessage(exception), exception);
            return new WeatherForecast(destination, "Weather lookup failed through MCP.", false);
        }
    }

    private Double number(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNumber() ? node.path(field).doubleValue() : null;
    }

    private Integer integer(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNumber() ? node.path(field).intValue() : null;
    }

    private Long longValue(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNumber() ? node.path(field).longValue() : null;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
