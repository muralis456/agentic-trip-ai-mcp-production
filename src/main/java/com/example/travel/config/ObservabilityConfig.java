package com.example.travel.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prevent accidental high-cardinality tags from creating unbounded metric
 * series. User/conversation/request IDs must never become metric labels.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    MeterFilter denyHighCardinalityTags() {
        return MeterFilter.denyNameStartsWith("agent.debug.");
    }
}
