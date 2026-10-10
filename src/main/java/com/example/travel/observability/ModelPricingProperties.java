package com.example.travel.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Optional provider/model pricing used only for transparent cost estimates in the dashboard. */
@Component
@ConfigurationProperties(prefix = "travel.observability")
public class ModelPricingProperties {
    private Map<String, ModelPrice> modelPricing = new LinkedHashMap<>();

    public Map<String, ModelPrice> getModelPricing() { return modelPricing; }
    public void setModelPricing(Map<String, ModelPrice> modelPricing) {
        this.modelPricing = modelPricing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(modelPricing);
    }

    public static class ModelPrice {
        private double inputUsdPerMillion;
        private double outputUsdPerMillion;

        public double getInputUsdPerMillion() { return inputUsdPerMillion; }
        public void setInputUsdPerMillion(double value) { inputUsdPerMillion = Math.max(0, value); }
        public double getOutputUsdPerMillion() { return outputUsdPerMillion; }
        public void setOutputUsdPerMillion(double value) { outputUsdPerMillion = Math.max(0, value); }
    }
}
