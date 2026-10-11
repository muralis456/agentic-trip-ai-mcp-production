package com.example.travel.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelPricingPropertiesTest {
    @Test
    void localModelSeparatesApiChargesFromComputeAllocation() {
        ModelPricingProperties pricing = new ModelPricingProperties();
        pricing.setLocalModels(Set.of("local-model"));
        pricing.setLocalComputeUsdPerHour(3.60);

        assertEquals(0.0, pricing.estimateProviderApiCostUsd("LOCAL-MODEL", 1_000_000, 1_000_000));
        assertEquals(1.80, pricing.estimateLocalInfrastructureCostUsd("local-model", 1_800_000), 0.000001);
    }

    @Test
    void paidModelUsesConfiguredInputAndOutputRates() {
        ModelPricingProperties pricing = new ModelPricingProperties();
        ModelPricingProperties.ModelPrice rate = new ModelPricingProperties.ModelPrice();
        rate.setInputUsdPerMillion(2.0);
        rate.setOutputUsdPerMillion(4.0);
        pricing.setModelPricing(Map.of("paid-model", rate));

        assertEquals(6.0, pricing.estimateProviderApiCostUsd("PAID-MODEL", 1_000_000, 1_000_000), 0.000001);
        assertEquals(0.0, pricing.estimateLocalInfrastructureCostUsd("paid-model", 10_000), 0.000001);
    }

    @Test
    void explicitPaidRateOverridesLocalModelClassificationWithoutDoubleCountingCompute() {
        ModelPricingProperties pricing = new ModelPricingProperties();
        pricing.setLocalModels(Set.of("shared-model"));
        pricing.setLocalComputeUsdPerHour(3.60);
        ModelPricingProperties.ModelPrice rate = new ModelPricingProperties.ModelPrice();
        rate.setInputUsdPerMillion(1.0);
        rate.setOutputUsdPerMillion(2.0);
        pricing.setModelPricing(Map.of("shared-model", rate));

        assertEquals(3.0, pricing.estimateProviderApiCostUsd("shared-model", 1_000_000, 1_000_000), 0.000001);
        assertEquals(0.0, pricing.estimateLocalInfrastructureCostUsd("shared-model", 3_600_000), 0.000001);
    }

    @Test
    void doesNotInventLocalComputeCostWhenNoRateIsConfigured() {
        ModelPricingProperties pricing = new ModelPricingProperties();
        pricing.setLocalModels(Set.of("local-model"));
        assertNull(pricing.estimateLocalInfrastructureCostUsd("local-model", 120_000));
        assertEquals(0.0, pricing.estimateLocalInfrastructureCostUsd("paid-model", 120_000));
    }

    @Test
    void doesNotMisreportUnconfiguredPaidModelAsFree() {
        ModelPricingProperties pricing = new ModelPricingProperties();
        assertNull(pricing.estimateProviderApiCostUsd("unknown-paid-model", 100, 100));
    }
}
