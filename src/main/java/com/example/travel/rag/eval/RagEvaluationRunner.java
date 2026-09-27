package com.example.travel.rag.eval;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.service.RoutedLlm;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Runs a repeatable retrieval benchmark against the currently indexed KB. */
@Service
public class RagEvaluationRunner {
    private final VectorStore vectorStore;
    private final RagEvaluationService evaluator;
    private final RagLlmJudgeService judgeService;
    private final RoutedLlm routedLlm;

    public RagEvaluationRunner(VectorStore vectorStore, RagEvaluationService evaluator, RagLlmJudgeService judgeService, RoutedLlm routedLlm) {
        this.vectorStore = vectorStore;
        this.evaluator = evaluator;
        this.judgeService = judgeService;
        this.routedLlm = routedLlm;
    }

    public RagEvaluationReport run(List<RagEvaluationCase> cases) {
        List<RagEvaluationReport.CaseResult> results = new ArrayList<>();
        double scoreSum = 0d;
        double groundedSum = 0d;
        int passed = 0;

        for (RagEvaluationCase testCase : cases) {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(testCase.query()).topK(5).build());
            String context = docs.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);
            List<String> sources = docs.stream().map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "knowledge-base"))).distinct().toList();
            RagEvaluationService.Evaluation evaluation = evaluator.evaluateRetrieval(
                    testCase.query(), context, sources, testCase.expectedKeywords(), testCase.expectedSources());
            boolean ok = evaluation.overallScore() >= testCase.minimumScore();
            if (ok) passed++;
            scoreSum += evaluation.overallScore();
            String answer = routedLlm.complete(AgentRole.FINAL,
                    "Answer the travel question using ONLY the supplied evidence. If evidence is insufficient, say so. Keep it concise.",
                    "QUESTION: " + testCase.query() + "\n\nEVIDENCE:\n" + context);
            RagLlmJudgeService.JudgeResult judge = judgeService.judge(answer, context, testCase.query());
            groundedSum += judge.groundedness();
            boolean finalOk = ok && judge.pass();
            if (!finalOk && ok) passed--;
            if (finalOk && !ok) passed++;
            results.add(new RagEvaluationReport.CaseResult(testCase.name(), evaluation.overallScore(), finalOk,
                    finalOk ? "retrieval_and_groundedness_passed" : "retrieval=" + ok + ", groundedness=" + judge.pass()));
        }
        int total = cases.size();
        return new RagEvaluationReport(total, passed, total == 0 ? 0d : (double) passed / total,
                total == 0 ? 0d : scoreSum / total, total == 0 ? 0d : groundedSum / total, results);
    }
}
