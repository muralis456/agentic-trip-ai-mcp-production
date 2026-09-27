package com.example.travel.agent;

import com.example.travel.graph.TravelState;
import com.example.travel.model.BudgetSummary;
import com.example.travel.tool.BudgetCalculatorTool;
import com.example.travel.tool.CurrencyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BudgetAgentService {

    private static final Logger log = LoggerFactory.getLogger(BudgetAgentService.class);

    private final BudgetCalculatorTool budgetCalculatorTool;
    private final CurrencyTool currencyTool;

    public BudgetAgentService(BudgetCalculatorTool budgetCalculatorTool, CurrencyTool currencyTool) {
        this.budgetCalculatorTool = budgetCalculatorTool;
        this.currencyTool = currencyTool;
    }

    public BudgetSummary assess(TravelState state) {
        // Deterministic @Tool-backed estimate (INR line items). Currency tool annotates local FX.
        BudgetSummary summary = budgetCalculatorTool.calculate(state);
        String local = currencyTool.currencyForDestination(state.destination());
        log.info("Budget agent total={} withinBudget={} localCurrency={}",
                summary.getEstimatedCost(), summary.isWithinBudget(), local);
        return summary;
    }
}
