package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

public class BudgetLineItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String category;
    private BigDecimal amountInr;
    private String notes;

    public BudgetLineItem() {
    }

    public BudgetLineItem(String category, BigDecimal amountInr, String notes) {
        this.category = category;
        this.amountInr = amountInr;
        this.notes = notes;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getAmountInr() {
        return amountInr;
    }

    public void setAmountInr(BigDecimal amountInr) {
        this.amountInr = amountInr;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
