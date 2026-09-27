package com.example.travel.graph;

import com.example.travel.model.AgentStep;
import com.example.travel.model.LlmExecutionResult;
import com.example.travel.service.LlmCallContext;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentStepEnricher {

    private AgentStepEnricher() {
    }

    public static Map<String, Object> apply(NodeAction<TravelState> node, TravelState state, String nodeName) throws Exception {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>(node.apply(state));
        long durationMs = System.currentTimeMillis() - started;
        List<LlmExecutionResult> llmCalls = LlmCallContext.consume();
        attach(result, state, durationMs, llmCalls);
        GraphExecutionLogger.llmStage(nodeName, state, llmCalls, durationMs);
        return result;
    }

    public static void attach(Map<String, Object> result, TravelState state, long durationMs) {
        attach(result, state, durationMs, LlmCallContext.consume());
    }

    private static void attach(Map<String, Object> result, TravelState state, long durationMs, List<LlmExecutionResult> llmCalls) {
        Object pipeline = result.get(TravelState.PIPELINE);
        if (!(pipeline instanceof List<?> steps)) {
            return;
        }
        for (Object step : steps) {
            if (step instanceof AgentStep agentStep) {
                if (agentStep.getDurationMs() <= 0) {
                    agentStep.setDurationMs(durationMs);
                }
                agentStep.setRetryCount(state.retryCount());
                agentStep.setAttempt(state.retryCount() + 1);
                if (agentStep.getModel() == null || agentStep.getModel().isBlank()) {
                    agentStep.setModel(LlmCallContext.joinedModels(llmCalls));
                }
                if (agentStep.getToolCalls() == null || agentStep.getToolCalls().isBlank()) {
                    agentStep.setToolCalls(LlmCallContext.joinedTools(llmCalls));
                }
                if (agentStep.getInputTokens() == 0) {
                    agentStep.setInputTokens(LlmCallContext.totalInputTokens(llmCalls));
                }
                if (agentStep.getOutputTokens() == 0) {
                    agentStep.setOutputTokens(LlmCallContext.totalOutputTokens(llmCalls));
                }
            }
        }
    }
}
