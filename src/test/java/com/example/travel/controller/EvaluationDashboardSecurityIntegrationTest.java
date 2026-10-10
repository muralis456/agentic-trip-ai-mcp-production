package com.example.travel.controller;

import com.example.travel.observability.AgentEvaluationLedger;
import com.example.travel.observability.AgentModelUsageLedger;
import com.example.travel.observability.ModelPricingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EvaluationDashboardController.class)
@Import({com.example.travel.security.SecurityConfig.class, EvaluationDashboardSecurityIntegrationTest.TestUsers.class})
class EvaluationDashboardSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AgentEvaluationLedger evaluations;

    @MockitoBean
    private AgentModelUsageLedger usage;

    @MockitoBean
    private ModelPricingProperties pricing;

    @Test
    void dashboardApiRejectsUnauthenticatedRequestsWith401() throws Exception {
        mvc.perform(get("/api/observability/dashboard"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(evaluations, usage);
    }

    @Test
    void evaluationPageRequiresAuthentication() throws Exception {
        mvc.perform(get("/evaluation"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")));
    }

    @Test
    void authenticatedDashboardUsesOnlyTheAuthenticatedUsernameForHistoryQueries() throws Exception {
        when(evaluations.recent(eq("alice"), anyInt())).thenReturn(List.of());
        when(evaluations.summary("alice")).thenReturn(Map.of("evaluations", 0L));
        when(usage.aggregateForUser("alice", 30)).thenReturn(List.of());

        mvc.perform(get("/api/observability/dashboard")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.evaluations").value(0))
                .andExpect(jsonPath("$.modelUsageWindowDays").value(30));

        verify(evaluations).recent("alice", 50);
        verify(evaluations).summary("alice");
        verify(usage).aggregateForUser("alice", 30);
        verify(evaluations, never()).recent(eq("bob"), anyInt());
        verify(evaluations, never()).summary("bob");
        verify(usage, never()).aggregateForUser(eq("bob"), anyInt());
    }

    private static String basic(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestUsers {
        @Bean
        @Primary
        PasswordEncoder testPasswordEncoder() {
            return NoOpPasswordEncoder.getInstance();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice").password("{noop}alice-password").roles("USER").build(),
                    User.withUsername("bob").password("{noop}bob-password").roles("USER").build());
        }
    }
}
