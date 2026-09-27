package com.example.travel.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import com.example.travel.rag.RagKnowledgeLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.travel.rag.eval.RagEvaluationCatalog;
import com.example.travel.rag.eval.RagEvaluationRunner;
import com.example.travel.rag.eval.RagEvaluationService;
import org.springframework.ai.vectorstore.VectorStore;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagEvaluationRunner evaluationRunner;
    private final RagEvaluationService evaluationService;
    private final com.example.travel.rag.eval.RagLlmJudgeService ragLlmJudgeService;
    private final RagKnowledgeLoader knowledgeLoader;

    public RagController(VectorStore vectorStore, JdbcTemplate jdbcTemplate, RagEvaluationRunner evaluationRunner, RagEvaluationService evaluationService,
                         com.example.travel.rag.eval.RagLlmJudgeService ragLlmJudgeService,
                         RagKnowledgeLoader knowledgeLoader) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.evaluationRunner = evaluationRunner;
        this.evaluationService = evaluationService;
        this.ragLlmJudgeService = ragLlmJudgeService;
        this.knowledgeLoader = knowledgeLoader;
    }

    /** Runs the deterministic retrieval benchmark against the current vector index. */
    @PostMapping("/evaluate")
    public Object evaluate() {
        return evaluationRunner.run(RagEvaluationCatalog.defaultCases());
    }

    /** Scores an already generated answer against retrieved RAG context. */
    @PostMapping("/groundedness")
    public Map<String, Object> groundedness(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String answer = body.getOrDefault("answer", "");
        String context = body.getOrDefault("context", "");
        double score = evaluationService.groundedness(answer, context);
        return Map.of("groundedness", score, "answerChars", answer.length(), "contextChars", context.length());
    }

    /** LLM-as-a-judge groundedness evaluation for CI/manual testing. */
    @PostMapping("/judge")
    public Object judge(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String answer = body.getOrDefault("answer", "");
        String context = body.getOrDefault("context", "");
        String request = body.getOrDefault("userRequest", "");
        return ragLlmJudgeService.judge(answer, context, request);
    }

    /** Rebuilds bundled knowledge and regenerates database-backed city/airport knowledge. */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        try {
            return knowledgeLoader.reindex();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to reindex RAG knowledge", ex);
        }
    }
}
