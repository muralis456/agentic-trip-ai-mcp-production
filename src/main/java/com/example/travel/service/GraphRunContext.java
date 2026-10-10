package com.example.travel.service;

import com.example.travel.graph.GraphExecutionLogger;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares LLM/Tavily budget counters and model policy with LangGraph parallel worker threads.
 */
@Component
public class GraphRunContext {

    private final AgentExecutionBudget executionBudget;
    private final ConcurrentHashMap<String, RunContext> active = new ConcurrentHashMap<>();

    public GraphRunContext(AgentExecutionBudget executionBudget) {
        this.executionBudget = executionBudget;
    }

    public void open(String threadId, AgentExecutionBudget.Counters budget, String policy) {\n        open(threadId, budget, policy, null);\n    }\n\n    public void open(String threadId, AgentExecutionBudget.Counters budget, String policy, String userId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        active.put(threadId, new RunContext(budget, policy, userId, threadId));
        GraphExecutionLogger.runContext("open", threadId, policy);
    }

    public void attach(String threadId) {
        RunContext context = active.get(threadId);
        if (context == null) {
            return;
        }
        if (context.budget != null) {
            executionBudget.use(context.budget);
        }
        ModelRoutingContext.set(context.policy);\n        ModelRoutingContext.setRunIdentity(context.userId, context.threadId);
    }

    public void detach() {
        ModelRoutingContext.clear();
        executionBudget.end();
    }

    public void close(String threadId) {
        if (threadId != null) {
            active.remove(threadId);
            GraphExecutionLogger.runContext("close", threadId, "-");
        }
    }

    private record RunContext(AgentExecutionBudget.Counters budget, String policy, String userId, String threadId) {
    }
}
