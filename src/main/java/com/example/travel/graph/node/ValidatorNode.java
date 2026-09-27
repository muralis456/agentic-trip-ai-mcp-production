package com.example.travel.graph.node;

import com.example.travel.agent.SemanticValidatorService;
import com.example.travel.agent.ValidatorAgentService;
import com.example.travel.graph.GraphExecutionLogger;
import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.PlanQualityScore;
import com.example.travel.model.SemanticValidationResult;
import com.example.travel.service.PlanQualityScorer;
import com.example.travel.rag.eval.RagLlmJudgeService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ValidatorNode implements NodeAction<TravelState> {

    private static final Logger log = LoggerFactory.getLogger(ValidatorNode.class);

    private final ValidatorAgentService validatorAgentService;
    private final SemanticValidatorService semanticValidatorService;
    private final PlanQualityScorer planQualityScorer;
    private final RagLlmJudgeService ragLlmJudgeService;

    public ValidatorNode(ValidatorAgentService validatorAgentService,
                          SemanticValidatorService semanticValidatorService,
                          PlanQualityScorer planQualityScorer,
                          RagLlmJudgeService ragLlmJudgeService) {
        this.validatorAgentService = validatorAgentService;
        this.semanticValidatorService = semanticValidatorService;
        this.planQualityScorer = planQualityScorer;
        this.ragLlmJudgeService = ragLlmJudgeService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        List<String> errors = new ArrayList<>(validatorAgentService.validate(state));
        SemanticValidationResult semantic = semanticValidatorService.review(state);
        List<String> semanticNotes = semanticValidatorService.issueNotes(semantic);
        PlanQualityScore quality = planQualityScorer.score(state, semantic);
        RagLlmJudgeService.JudgeResult ragJudge = ragLlmJudgeService.judge(state);
        if (state.ragSufficient() && !ragJudge.pass()) {
            errors.add("RAG groundedness failed: " + ragJudge.reason());
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.VALIDATION_ERRORS, errors);
        updates.put(TravelState.SEMANTIC_NOTES, semanticNotes);
        updates.put(TravelState.SEMANTIC_VALIDATION, semantic);
        updates.put(TravelState.PLAN_QUALITY, quality);
        updates.put(TravelState.RAG_GROUNDEDNESS, ragJudge.groundedness());
        updates.put(TravelState.RAG_JUDGE_PASS, ragJudge.pass());
        updates.put(TravelState.RAG_JUDGE_REASON, ragJudge.reason());

        String detail = "overall=" + String.format("%.2f", quality.getOverall())
                + " | ragGroundedness=" + String.format("%.2f", ragJudge.groundedness())
                + " | ragJudge=" + (ragJudge.pass() ? "PASS" : "FAIL");
        if (!errors.isEmpty()) {
            detail += " | " + String.join("; ", errors);
        }
        if (!semanticNotes.isEmpty()) {
            detail += " | semantic: " + String.join("; ", semanticNotes);
        }
        boolean pass = errors.isEmpty() && !semantic.failed() && quality.passes();
        if (!pass) {
            log.warn("Validator rejected plan: errors={} semanticIssues={} semanticScore={} quality={} retry={}/{}",
                    errors, semanticNotes, semantic.getScore(), quality.getOverall(),
                    state.retryCount(), state.maxRetries());
        }
        GraphExecutionLogger.validation(state, quality.getOverall(), pass, errors, semanticNotes);
        updates.putAll(TravelState.trace(TravelGraphNodes.VALIDATOR, pass ? "ok" : "warn", detail));
        return updates;
    }
}
