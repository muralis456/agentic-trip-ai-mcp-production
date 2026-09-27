package com.example.travel.graph.node;

import com.example.travel.graph.TravelGraphNodes;
import com.example.travel.graph.TravelState;
import com.example.travel.model.BudgetSummary;
import com.example.travel.agent.BudgetAgentService;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BudgetNode implements NodeAction<TravelState> {

    private final BudgetAgentService budgetAgentService;

    public BudgetNode(BudgetAgentService budgetAgentService) {
        this.budgetAgentService = budgetAgentService;
    }

    @Override
    public Map<String, Object> apply(TravelState state) {
        if (!state.shouldExecuteTask("budget")) {
            BudgetSummary skipped = new BudgetSummary();
            skipped.setWithinBudget(true);
            skipped.setAssessment("Budget not requested for this query.");
            skipped.setEstimatedCost(java.math.BigDecimal.ZERO);
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put(TravelState.BUDGET_SUMMARY, skipped);
            skip.putAll(TravelState.trace(TravelGraphNodes.BUDGET, "skip", "not requested"));
            return skip;
        }
        BudgetSummary summary = budgetAgentService.assess(state);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.BUDGET_SUMMARY, summary);
        updates.putAll(TravelState.trace(TravelGraphNodes.BUDGET, summary.isWithinBudget() ? "ok" : "warn",
                summary.getAssessment()));
        return updates;
    }
}
