package com.example.travel.tool;

import com.example.travel.model.WeatherForecast;
import com.example.travel.service.McpWeatherClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String geocodeUrl;
    private final String currentUrl;
    private final String forecastUrl;
    private final String apiKey;
    private final ObjectProvider<McpWeatherClient> mcpWeatherClient;

    public WeatherTool(RestTemplate restTemplate,
                       ObjectMapper objectMapper,
                       @Value("${travel.weather.geocode-url:https://api.openweathermap.org/geo/1.0/direct}") String geocodeUrl,
                       @Value("${travel.weather.current-url:https://api.openweathermap.org/data/2.5/weather}") String currentUrl,
                       @Value("${travel.weather.forecast-url:https://api.openweathermap.org/data/2.5/forecast}") String forecastUrl,
                       @Value("${travel.weather.api-key:}") String apiKey,
                       ObjectProvider<McpWeatherClient> mcpWeatherClient) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.geocodeUrl = geocodeUrl;
        this.currentUrl = currentUrl;
        this.forecastUrl = forecastUrl;
        this.apiKey = apiKey;
        this.mcpWeatherClient = mcpWeatherClient;
    }

    public WeatherForecast forecast(String destination, LocalDate start, LocalDate end) {
        return forecast(destination, start, end, "Weather forecast for " + destination);
    }

    public WeatherForecast forecast(String destination, LocalDate start, LocalDate end, String userInput) {
        McpWeatherClient client = mcpWeatherClient.getIfAvailable();
        if (client != null) {
            return client.forecast(destination, start, end, userInput);
        }

        LocalDate from = start == null ? LocalDate.now() : start;
        LocalDate to = end == null ? from.plusDays(5) : end;
        try {
            if (apiKey == null || apiKey.isBlank()) {
                return new WeatherForecast(destination, "OpenWeather API key is not configured.", false);
            }

            JsonNode geo = objectMapper.readTree(restTemplate.getForObject(
                    UriComponentsBuilder.fromUriString(geocodeUrl)
                            .queryParam("q", destination)
                            .queryParam("limit", 1)
                            .queryParam("appid", apiKey)
                            .build().toUriString(), String.class));
            if (!geo.isArray() || geo.isEmpty()) {
                return new WeatherForecast(destination, "Weather location could not be resolved.", false);
            }

            JsonNode place = geo.get(0);
            double lat = place.path("lat").asDouble();
            double lon = place.path("lon").asDouble();
            String resolvedLocation = place.path("name").asText(destination);

            JsonNode currentRoot = objectMapper.readTree(restTemplate.getForObject(
                    UriComponentsBuilder.fromUriString(currentUrl)
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("units", "metric")
                            .queryParam("lang", "en")
                            .queryParam("appid", apiKey)
                            .build().toUriString(), String.class));

            JsonNode forecastRoot = objectMapper.readTree(restTemplate.getForObject(
                    UriComponentsBuilder.fromUriString(forecastUrl)
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("units", "metric")
                            .queryParam("lang", "en")
                            .queryParam("appid", apiKey)
                            .build().toUriString(), String.class));

            String timezone = timezoneLabel(forecastRoot.path("city").path("timezone").asInt(0));
            WeatherForecast result = new WeatherForecast(resolvedLocation, "", false);
            result.setCurrent(mapCurrent(currentRoot, timezone));
            List<WeatherForecast.DailyForecast> days = aggregateDailyForecast(forecastRoot, from, to);
            result.setDays(days);

            boolean rain = currentRain(result.getCurrent()) || days.stream()
                    .anyMatch(day -> day.getRainProbability() != null && day.getRainProbability() >= 40);
            result.setRainLikely(rain);
            result.setSummary(rain
                    ? "Rain or precipitation is possible during the travel window; keep weather-flexible plans available."
                    : "Current conditions and the available 5-day outlook are broadly suitable for outdoor travel.");
            return result;
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            String body = exception.getResponseBodyAsString();
            String providerMessage = extractProviderMessage(body);
            String reason = "OpenWeather request failed (HTTP " + exception.getStatusCode().value() + ")"
                    + (providerMessage.isBlank() ? "" : ": " + providerMessage);
            log.warn("Weather lookup failed destination={} reason={}", destination, reason);
            return new WeatherForecast(destination, reason, false);
        } catch (Exception exception) {
            log.warn("Weather lookup failed for {}: {}", destination, exception.getMessage(), exception);
            return new WeatherForecast(destination, "OpenWeather lookup failed: " + safeMessage(exception), false);
        }
    }

    private WeatherForecast.CurrentWeather mapCurrent(JsonNode root, String timezone) {
        JsonNode main = root.path("main");
        JsonNode weather = firstWeather(root);
        JsonNode wind = root.path("wind");
        JsonNode clouds = root.path("clouds");
        JsonNode rain = root.path("rain");
        JsonNode snow = root.path("snow");
        JsonNode sys = root.path("sys");

        WeatherForecast.CurrentWeather current = new WeatherForecast.CurrentWeather();
        current.setTemperature(numberOrNull(main, "temp"));
        current.setFeelsLike(numberOrNull(main, "feels_like"));
        current.setHumidity(integerOrNull(main, "humidity"));
        current.setPressure(integerOrNull(main, "pressure"));
        current.setDewPoint(null);
        current.setUvIndex(null);
        current.setClouds(integerOrNull(clouds, "all"));
        current.setVisibilityMeters(integerOrNull(root, "visibility"));
        current.setWindSpeed(numberOrNull(wind, "speed"));
        current.setWindGust(numberOrNull(wind, "gust"));
        current.setWindDeg(integerOrNull(wind, "deg"));
        current.setRain1h(numberOrNull(rain, "1h"));
        current.setSnow1h(numberOrNull(snow, "1h"));
        current.setObservedAt(longOrNull(root, "dt"));
        current.setSunrise(longOrNull(sys, "sunrise"));
        current.setSunset(longOrNull(sys, "sunset"));
        current.setTimezone(timezone);
        current.setCondition(weather.path("main").asText(""));
        current.setDescription(weather.path("description").asText(""));
        current.setIcon(weather.path("icon").asText(""));
        return current;
    }

    private List<WeatherForecast.DailyForecast> aggregateDailyForecast(JsonNode root, LocalDate from, LocalDate to) {
        Map<LocalDate, List<JsonNode>> grouped = new TreeMap<>();
        JsonNode list = root.path("list");
        if (!list.isArray()) return List.of();

        for (JsonNode item : list) {
            LocalDate date = parseForecastDate(item.path("dt_txt").asText(""));
            if (date != null && !date.isBefore(from) && !date.isAfter(to)) {
                grouped.computeIfAbsent(date, ignored -> new ArrayList<>()).add(item);
            }
        }

        List<WeatherForecast.DailyForecast> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<JsonNode>> entry : grouped.entrySet()) {
            Double high = null;
            Double low = null;
            Integer maxPop = null;
            JsonNode representative = null;
            double bestPop = -1;

            for (JsonNode item : entry.getValue()) {
                JsonNode main = item.path("main");
                if (main.path("temp_max").isNumber()) {
                    high = high == null ? main.path("temp_max").doubleValue()
                            : Math.max(high, main.path("temp_max").doubleValue());
                }
                if (main.path("temp_min").isNumber()) {
                    low = low == null ? main.path("temp_min").doubleValue()
                            : Math.min(low, main.path("temp_min").doubleValue());
                }
                if (item.path("pop").isNumber()) {
                    int pop = (int) Math.round(item.path("pop").doubleValue() * 100.0);
                    maxPop = maxPop == null ? pop : Math.max(maxPop, pop);
                    if (pop > bestPop) {
                        bestPop = pop;
                        representative = item;
                    }
                }
            }

            if (representative == null) {
                representative = entry.getValue().get(entry.getValue().size() / 2);
            }
            JsonNode weather = firstWeather(representative);
            days.add(new WeatherForecast.DailyForecast(
                    entry.getKey().toString(),
                    weather.path("description").asText(weather.path("main").asText("Forecast")),
                    weather.path("icon").asText(""),
                    high,
                    low,
                    maxPop));
        }
        return days;
    }

    private JsonNode firstWeather(JsonNode node) {
        JsonNode weather = node.path("weather");
        return weather.isArray() && !weather.isEmpty()
                ? weather.get(0)
                : objectMapper.createObjectNode();
    }

    private LocalDate parseForecastDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String timezoneLabel(int offsetSeconds) {
        if (offsetSeconds == 0) return "UTC";
        int absolute = Math.abs(offsetSeconds);
        return String.format(java.util.Locale.ROOT, "UTC%s%02d:%02d",
                offsetSeconds >= 0 ? "+" : "-", absolute / 3600, (absolute % 3600) / 60);
    }

    private boolean currentRain(WeatherForecast.CurrentWeather current) {
        if (current == null) return false;
        return (current.getRain1h() != null && current.getRain1h() > 0)
                || (current.getSnow1h() != null && current.getSnow1h() > 0)
                || "Rain".equalsIgnoreCase(current.getCondition())
                || "Drizzle".equalsIgnoreCase(current.getCondition())
                || "Thunderstorm".equalsIgnoreCase(current.getCondition());
    }

    @Tool(description = "Get OpenWeather current conditions and free 5-day/3-hour forecast for a destination between two dates (yyyy-MM-dd).")
    public String forecastText(
            @ToolParam(description = "Destination city or country") String destination,
            @ToolParam(description = "Start date yyyy-MM-dd") String startDate,
            @ToolParam(description = "End date yyyy-MM-dd") String endDate) {
        LocalDate start = parseDate(startDate, LocalDate.now());
        LocalDate end = parseDate(endDate, start.plusDays(5));
        return forecast(destination, start, end).toDisplay();
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Double numberOrNull(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNumber() ? node.path(field).doubleValue() : null;
    }

    private Integer integerOrNull(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNumber() ? node.path(field).intValue() : null;
    }

    private Long longOrNull(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNumber() ? node.path(field).longValue() : null;
    }

    private String extractProviderMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            return objectMapper.readTree(body).path("message").asText("");
        } catch (Exception ignored) {
            return body.trim();
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
