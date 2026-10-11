package com.example.travel.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configurable estimates for model API charges and local model compute allocation.
 * API rates should be updated from the provider's billing page; local compute is
 * an allocation estimate, not a meter reading of electricity or cloud invoices.
 */
@Component
@ConfigurationProperties(prefix = "travel.observability")
public class ModelPricingProperties {
    private Map<String, ModelPrice> modelPricing = new LinkedHashMap<>();
    private Set<String> localModels = new LinkedHashSet<>(Set.of("llama3.2:3b", "tev1:4b"));
    private double localComputeUsdPerHour = 0.10;

    public Map<String, ModelPrice> getModelPricing() { return modelPricing; }
    public void setModelPricing(Map<String, ModelPrice> modelPricing) {
        this.modelPricing = modelPricing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(modelPricing);
    }
    public Set<String> getLocalModels() { return localModels; }
    public void setLocalModels(Set<String> localModels) {
        this.localModels = localModels == null ? new LinkedHashSet<>() : new LinkedHashSet<>(localModels);
    }
    public double getLocalComputeUsdPerHour() { return localComputeUsdPerHour; }
    public void setLocalComputeUsdPerHour(double value) {
        localComputeUsdPerHour = Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    public boolean isLocalModel(String model) {
        if (model == null || model.isBlank()) return false;
        String normalized = normalize(model);
        return localModels.stream().anyMatch(candidate -> normalize(candidate).equals(normalized));
    }

    public Double estimateProviderApiCostUsd(String model, long inputTokens, long outputTokens) {
        ModelPrice rate = modelPricing.get(model);
        if (rate == null) {
            String normalized = normalize(model);
            rate = modelPricing.entrySet().stream()
                    .filter(entry -> normalize(entry.getKey()).equals(normalized))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        // Explicit non-zero rates indicate a paid route even when the model name
        // is also commonly used locally. Local-only models remain $0 when they
        // have no configured rates or their configured rates are both zero.
        if (isLocalModel(model) && (rate == null
                || (rate.getInputUsdPerMillion() == 0 && rate.getOutputUsdPerMillion() == 0))) return 0.0;
        if (rate == null) return null;
        return (Math.max(0, inputTokens) * rate.getInputUsdPerMillion()
                + Math.max(0, outputTokens) * rate.getOutputUsdPerMillion()) / 1_000_000.0;
    }

    public double estimateLocalInfrastructureCostUsd(String model, long durationMs) {
        if (!isLocalModel(model)) return 0.0;
        ModelPrice rate = modelPricing.get(model);
        if (rate == null) {
            String normalized = normalize(model);
            rate = modelPricing.entrySet().stream()
                    .filter(entry -> normalize(entry.getKey()).equals(normalized))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        // A non-zero configured token rate means this model is routed to a paid
        // provider; do not allocate local-compute cost to the same call.
        if (rate != null && (rate.getInputUsdPerMillion() > 0 || rate.getOutputUsdPerMillion() > 0)) return 0.0;
        return Math.max(0, durationMs) * localComputeUsdPerHour / 3_600_000.0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9_.:-]", "_");
    }

    public static class ModelPrice {
        private double inputUsdPerMillion;
        private double outputUsdPerMillion;

        public double getInputUsdPerMillion() { return inputUsdPerMillion; }
        public void setInputUsdPerMillion(double value) { inputUsdPerMillion = Double.isFinite(value) ? Math.max(0, value) : 0; }
        public double getOutputUsdPerMillion() { return outputUsdPerMillion; }
        public void setOutputUsdPerMillion(double value) { outputUsdPerMillion = Double.isFinite(value) ? Math.max(0, value) : 0; }
    }
}
