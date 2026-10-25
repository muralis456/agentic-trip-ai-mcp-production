package com.example.travel.controller;

import com.example.travel.rag.RagKnowledgeLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import com.example.travel.rag.eval.RagEvaluationCatalog;
import com.example.travel.rag.eval.RagEvaluationRunner;
import com.example.travel.rag.eval.RagEvaluationService;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private static final int MAX_ANSWER_CHARS = 12_000;
    private static final int MAX_CONTEXT_CHARS = 32_000;
    private static final int MAX_REQUEST_CHARS = 4_000;

    private final RagEvaluationRunner evaluationRunner;
    private final RagEvaluationService evaluationService;
    private final com.example.travel.rag.eval.RagLlmJudgeService ragLlmJudgeService;
    private final RagKnowledgeLoader knowledgeLoader;

    public RagController(RagEvaluationRunner evaluationRunner, RagEvaluationService evaluationService,
                         com.example.travel.rag.eval.RagLlmJudgeService ragLlmJudgeService,
                         RagKnowledgeLoader knowledgeLoader) {
        this.evaluationRunner = evaluationRunner;
        this.evaluationService = evaluationService;
        this.ragLlmJudgeService = ragLlmJudgeService;
        this.knowledgeLoader = knowledgeLoader;
    }

    /** Runs the deterministic retrieval benchmark against the current vector index. */
    @PostMapping("/evaluate")
    @PreAuthorize("hasRole('ADMIN')")
    public Object evaluate() {
        return evaluationRunner.run(RagEvaluationCatalog.defaultCases());
    }

    /** Scores an already generated answer against retrieved RAG context. */
    @PostMapping("/groundedness")
    public Map<String, Object> groundedness(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String answer = boundedText(body, "answer", MAX_ANSWER_CHARS);
        String context = boundedText(body, "context", MAX_CONTEXT_CHARS);
        double score = evaluationService.groundedness(answer, context);
        return Map.of("groundedness", score, "answerChars", answer.length(), "contextChars", context.length());
    }

    /** LLM-as-a-judge groundedness evaluation for CI/manual testing. */
    @PostMapping("/judge")
    @PreAuthorize("hasRole('ADMIN')")
    public Object judge(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String answer = boundedText(body, "answer", MAX_ANSWER_CHARS);
        String context = boundedText(body, "context", MAX_CONTEXT_CHARS);
        String request = boundedText(body, "userRequest", MAX_REQUEST_CHARS);
        return ragLlmJudgeService.judge(answer, context, request);
    }

    private String boundedText(Map<String, String> body, String field, int maxChars) {
        String value = body == null ? null : body.get(field);
        if (value == null) {
            return "";
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(field + " must be at most " + maxChars + " characters.");
        }
        return value;
    }

    /** Rebuilds bundled knowledge and regenerates database-backed city/airport knowledge. */
    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reindex() {
        try {
            return knowledgeLoader.reindex();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to reindex RAG knowledge", ex);
        }
    }
}
