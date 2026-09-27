package com.example.travel.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Component
public class CurrencyTool {

    private static final Logger log = LoggerFactory.getLogger(CurrencyTool.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String ratesUrl;

    public CurrencyTool(RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        @Value("${travel.currency.rates-url:https://api.frankfurter.app/latest}") String ratesUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.ratesUrl = ratesUrl;
    }

    public String currencyForDestination(String destination) {
        String value = destination == null ? "" : destination.toLowerCase(Locale.ROOT);
        if (value.contains("japan") || value.contains("tokyo") || value.contains("osaka") || value.contains("kyoto")) {
            return "JPY";
        }
        if (value.contains("united states") || value.contains("usa") || value.contains("new york")) {
            return "USD";
        }
        if (value.contains("dubai") || value.contains("uae") || value.contains("abu dhabi")) {
            return "AED";
        }
        if (value.contains("thai") || value.contains("bangkok") || value.contains("phuket")) {
            return "THB";
        }
        if (value.contains("paris") || value.contains("france") || value.contains("rome") || value.contains("italy")
                || value.contains("germany") || value.contains("spain") || value.contains("europe")) {
            return "EUR";
        }
        if (value.contains("india") || value.contains("delhi") || value.contains("mumbai") || value.contains("bengaluru")
                || value.contains("bangalore") || value.contains("goa")) {
            return "INR";
        }
        return "USD";
    }

    @Tool(description = "Detect the local currency code for a travel destination (JPY, USD, EUR, INR, etc.).")
    public String detectCurrency(
            @ToolParam(description = "Destination city or country") String destination) {
        return currencyForDestination(destination);
    }

    @Tool(description = "Convert an amount from a foreign currency into INR using live rates when available.")
    public String convertToInr(
            @ToolParam(description = "Amount in the source currency") double amount,
            @ToolParam(description = "Source currency code, e.g. JPY") String fromCurrency) {
        return toInr(BigDecimal.valueOf(amount), fromCurrency).toPlainString();
    }

    public BigDecimal fromInr(BigDecimal amountInr, String toCurrency) {
        if (amountInr == null) {
            return BigDecimal.ZERO;
        }
        String to = toCurrency == null ? "INR" : toCurrency.toUpperCase(Locale.ROOT);
        if ("INR".equals(to)) {
            return amountInr.setScale(0, RoundingMode.HALF_UP);
        }
        BigDecimal oneLocalInInr = toInr(BigDecimal.ONE, to);
        if (oneLocalInInr.compareTo(BigDecimal.ZERO) <= 0) {
            return amountInr;
        }
        return amountInr.divide(oneLocalInInr, 0, RoundingMode.HALF_UP);
    }

    public BigDecimal toInr(BigDecimal amount, String fromCurrency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        String from = fromCurrency == null ? "INR" : fromCurrency.toUpperCase(Locale.ROOT);
        if ("INR".equals(from)) {
            return amount.setScale(0, RoundingMode.HALF_UP);
        }
        try {
            String url = UriComponentsBuilder.fromUriString(ratesUrl)
                    .queryParam("from", from)
                    .queryParam("to", "INR")
                    .build()
                    .toUriString();
            JsonNode root = objectMapper.readTree(restTemplate.getForObject(url, String.class));
            String rateText = root.path("rates").path("INR").asString("0");
            double rate;
            try {
                rate = Double.parseDouble(rateText);
            } catch (NumberFormatException ignored) {
                rate = 0;
            }
            if (rate <= 0) {
                return fallback(amount, from);
            }
            return amount.multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP);
        } catch (Exception exception) {
            log.warn("Currency conversion failed {} -> INR", from, exception);
            return fallback(amount, from);
        }
    }

    private BigDecimal fallback(BigDecimal amount, String from) {
        double rate = switch (from) {
            case "JPY" -> 0.56;
            case "EUR" -> 100;
            case "USD" -> 83;
            case "AED" -> 22.6;
            case "THB" -> 2.4;
            default -> 83;
        };
        return amount.multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP);
    }
}
