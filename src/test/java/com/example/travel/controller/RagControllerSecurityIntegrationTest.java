package com.example.travel.controller;

import com.example.travel.rag.RagKnowledgeLoader;
import com.example.travel.rag.eval.RagEvaluationRunner;
import com.example.travel.rag.eval.RagEvaluationService;
import com.example.travel.rag.eval.RagLlmJudgeService;
import com.example.travel.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagController.class)
@Import({SecurityConfig.class, RagControllerSecurityIntegrationTest.TestUsers.class})
class RagControllerSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RagEvaluationRunner evaluationRunner;

    @MockitoBean
    private RagEvaluationService evaluationService;

    @MockitoBean
    private RagLlmJudgeService judgeService;

    @MockitoBean
    private RagKnowledgeLoader knowledgeLoader;

    @Test
    void regularUserCannotStartModelBackedRagJudge() throws Exception {
        mvc.perform(post("/api/rag/judge")
                        .with(user("alice").roles("USER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"answer":"answer","context":"evidence","userRequest":"question"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(judgeService);
    }

    @Test
    void regularUserCannotRunRagBenchmark() throws Exception {
        mvc.perform(post("/api/rag/evaluate")
                        .with(user("alice").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(evaluationRunner);
    }

    @Test
    void adminCanStartModelBackedRagJudge() throws Exception {
        when(judgeService.judge("answer", "evidence", "question"))
                .thenReturn(new RagLlmJudgeService.JudgeResult(1, 1, 0, true, "grounded"));

        mvc.perform(post("/api/rag/judge")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"answer":"answer","context":"evidence","userRequest":"question"}
                                """))
                .andExpect(status().isOk());

        verify(judgeService).judge("answer", "evidence", "question");
    }

    @Test
    void oversizedGroundednessInputIsRejectedBeforeScoring() throws Exception {
        String body = "{\"answer\":\"" + "x".repeat(12_001) + "\",\"context\":\"evidence\"}";

        mvc.perform(post("/api/rag/groundedness")
                        .with(user("alice").roles("USER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(evaluationService);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestUsers {
        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice").password("{noop}alice-password").roles("USER").build(),
                    User.withUsername("admin").password("{noop}admin-password").roles("ADMIN").build());
        }
    }
}
