package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class WeatherForecast implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String location;
    private String summary;
    private boolean rainLikely;
    private List<DailyForecast> days = new ArrayList<>();
    private CurrentWeather current = new CurrentWeather();

    public WeatherForecast() {
    }

    public WeatherForecast(String location, String summary, boolean rainLikely) {
        this.location = location;
        this.summary = summary;
        this.rainLikely = rainLikely;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<DailyForecast> getDays() {
        return days == null ? List.of() : days;
    }

    public void setDays(List<DailyForecast> days) {
        this.days = days == null ? new ArrayList<>() : new ArrayList<>(days);
    }

    public CurrentWeather getCurrent() {
        return current == null ? new CurrentWeather() : current;
    }

    public void setCurrent(CurrentWeather current) {
        this.current = current == null ? new CurrentWeather() : current;
    }

    public boolean hasCurrentDetails() {
        return current != null && current.getTemperature() != null;
    }

    /**
     * Returns true only when the provider returned actual weather observations
     * or at least one usable forecast day. Location/summary text alone is not
     * considered weather data.
     */
    public boolean hasWeatherData() {
        return hasCurrentDetails() || getDays().stream().anyMatch(day ->
                day != null && (day.getHigh() != null || day.getLow() != null ||
                        !isBlank(day.getCondition())));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isRainLikely() {
        return rainLikely;
    }

    public void setRainLikely(boolean rainLikely) {
        this.rainLikely = rainLikely;
    }


    public static class CurrentWeather implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private Double temperature;
        private Double feelsLike;
        private Integer humidity;
        private Integer pressure;
        private Double dewPoint;
        private Double uvIndex;
        private Integer clouds;
        private Integer visibilityMeters;
        private Double windSpeed;
        private Double windGust;
        private Integer windDeg;
        private Double rain1h;
        private Double snow1h;
        private Long observedAt;
        private Long sunrise;
        private Long sunset;
        private String timezone;
        private String condition;
        private String description;
        private String icon;

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Double getFeelsLike() { return feelsLike; }
        public void setFeelsLike(Double feelsLike) { this.feelsLike = feelsLike; }
        public Integer getHumidity() { return humidity; }
        public void setHumidity(Integer humidity) { this.humidity = humidity; }
        public Integer getPressure() { return pressure; }
        public void setPressure(Integer pressure) { this.pressure = pressure; }
        public Double getDewPoint() { return dewPoint; }
        public void setDewPoint(Double dewPoint) { this.dewPoint = dewPoint; }
        public Double getUvIndex() { return uvIndex; }
        public void setUvIndex(Double uvIndex) { this.uvIndex = uvIndex; }
        public Integer getClouds() { return clouds; }
        public void setClouds(Integer clouds) { this.clouds = clouds; }
        public Integer getVisibilityMeters() { return visibilityMeters; }
        public void setVisibilityMeters(Integer visibilityMeters) { this.visibilityMeters = visibilityMeters; }
        public Double getWindSpeed() { return windSpeed; }
        public void setWindSpeed(Double windSpeed) { this.windSpeed = windSpeed; }
        public Double getWindGust() { return windGust; }
        public void setWindGust(Double windGust) { this.windGust = windGust; }
        public Integer getWindDeg() { return windDeg; }
        public void setWindDeg(Integer windDeg) { this.windDeg = windDeg; }
        public Double getRain1h() { return rain1h; }
        public void setRain1h(Double rain1h) { this.rain1h = rain1h; }
        public Double getSnow1h() { return snow1h; }
        public void setSnow1h(Double snow1h) { this.snow1h = snow1h; }
        public Long getObservedAt() { return observedAt; }
        public void setObservedAt(Long observedAt) { this.observedAt = observedAt; }
        public Long getSunrise() { return sunrise; }
        public void setSunrise(Long sunrise) { this.sunrise = sunrise; }
        public Long getSunset() { return sunset; }
        public void setSunset(Long sunset) { this.sunset = sunset; }
        public String getTimezone() { return timezone; }
        public void setTimezone(String timezone) { this.timezone = timezone; }
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
    }

    public static class DailyForecast implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String date;
        private String condition;
        private String icon;
        private Double high;
        private Double low;
        private Integer rainProbability;

        public DailyForecast() {
        }

        public DailyForecast(String date, String condition, String icon, Double high, Double low, Integer rainProbability) {
            this.date = date;
            this.condition = condition;
            this.icon = icon;
            this.high = high;
            this.low = low;
            this.rainProbability = rainProbability;
        }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public Double getHigh() { return high; }
        public void setHigh(Double high) { this.high = high; }
        public Double getLow() { return low; }
        public void setLow(Double low) { this.low = low; }
        public Integer getRainProbability() { return rainProbability; }
        public void setRainProbability(Integer rainProbability) { this.rainProbability = rainProbability; }
    }

    public String toDisplay() {
        return (location == null ? "" : location + ": ") + (summary == null ? "" : summary);
    }
}
