package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BudgetSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String budgetLevel;
    private BigDecimal estimatedCost;
    private BigDecimal remaining;
    private String currency = "INR";
    private String assessment;
    private boolean withinBudget;
    private List<BudgetLineItem> lineItems = new ArrayList<>();

    public String getBudgetLevel() {
        return budgetLevel;
    }

    public void setBudgetLevel(String budgetLevel) {
        this.budgetLevel = budgetLevel;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public BigDecimal getRemaining() {
        return remaining;
    }

    public void setRemaining(BigDecimal remaining) {
        this.remaining = remaining;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getAssessment() {
        return assessment;
    }

    public void setAssessment(String assessment) {
        this.assessment = assessment;
    }

    public boolean isWithinBudget() {
        return withinBudget;
    }

    public void setWithinBudget(boolean withinBudget) {
        this.withinBudget = withinBudget;
    }

    public List<BudgetLineItem> getLineItems() {
        return lineItems;
    }

    public void setLineItems(List<BudgetLineItem> lineItems) {
        this.lineItems = lineItems == null ? new ArrayList<>() : lineItems;
    }

    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        for (BudgetLineItem item : lineItems) {
            sb.append(item.getCategory()).append(": ₹").append(item.getAmountInr()).append('\n');
        }
        sb.append("Total: ₹").append(estimatedCost);
        if (remaining != null) {
            sb.append("\nRemaining: ₹").append(remaining);
        }
        if (assessment != null) {
            sb.append('\n').append(assessment);
        }
        return sb.toString().trim();
    }
}
