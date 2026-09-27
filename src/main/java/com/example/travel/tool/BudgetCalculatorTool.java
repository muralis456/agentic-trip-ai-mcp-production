package com.example.travel.tool;

import com.example.travel.graph.TravelState;
import com.example.travel.model.BudgetLineItem;
import com.example.travel.model.BudgetSummary;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class BudgetCalculatorTool {

    private final CurrencyTool currencyTool;

    public BudgetCalculatorTool(CurrencyTool currencyTool) {
        this.currencyTool = currencyTool;
    }

    public BudgetSummary calculate(TravelState state) {
        long nights = state.nights();
        int travelers = Math.max(state.travelers(), 1);
        BigDecimal factor = state.costFactor();
        String dest = state.destination() == null ? "" : state.destination();
        String localCurrency = currencyTool.currencyForDestination(dest);
        boolean domestic = "INR".equals(localCurrency);

        BigDecimal flightLocal = unit(domestic ? 8_000 : 55_000).multiply(BigDecimal.valueOf(travelers)).multiply(factor);
        BigDecimal hotelLocal = unit(domestic ? 4_500 : 7_000).multiply(BigDecimal.valueOf(nights)).multiply(factor);
        BigDecimal foodLocal = unit(domestic ? 1_500 : 2_800).multiply(BigDecimal.valueOf(nights * travelers)).multiply(factor);
        BigDecimal transportLocal = unit(domestic ? 800 : 2_500).multiply(BigDecimal.valueOf(nights)).multiply(factor);
        BigDecimal activitiesLocal = unit(domestic ? 1_200 : 2_200).multiply(BigDecimal.valueOf(nights * travelers)).multiply(factor);

        if (style(state).contains("luxury") || "high".equalsIgnoreCase(state.budgetLabel())) {
            hotelLocal = hotelLocal.multiply(BigDecimal.valueOf(1.6));
            activitiesLocal = activitiesLocal.multiply(BigDecimal.valueOf(1.4));
        } else if (style(state).contains("budget") || "low".equalsIgnoreCase(state.budgetLabel()) || state.hotelCheaper()) {
            hotelLocal = hotelLocal.multiply(BigDecimal.valueOf(0.75));
        }
        if ("cheapest".equalsIgnoreCase(state.flightPreference())) {
            flightLocal = flightLocal.multiply(BigDecimal.valueOf(0.9));
        }

        // Line items are INR estimates for an India-origin traveler (₹55k Japan fare, etc.).
        // CurrencyTool is used to annotate local-currency equivalents, not to re-rate these figures.
        BigDecimal flight = flightLocal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal hotel = hotelLocal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal food = foodLocal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal transport = transportLocal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal activities = activitiesLocal.setScale(0, RoundingMode.HALF_UP);

        BigDecimal total = flight.add(hotel).add(food).add(transport).add(activities);
        BigDecimal ceiling = state.budget();
        boolean within = ceiling == null || total.compareTo(ceiling) <= 0;
        BigDecimal remaining = ceiling == null ? null : ceiling.subtract(total);

        List<BudgetLineItem> items = new ArrayList<>();
        items.add(new BudgetLineItem("Flight", flight, travelers + " traveler(s)"));
        items.add(new BudgetLineItem("Hotel", hotel, nights + " night(s)"));
        items.add(new BudgetLineItem("Food", food, "meals"));
        items.add(new BudgetLineItem("Transport", transport, "local transit"));
        items.add(new BudgetLineItem("Activities", activities, "tickets and experiences"));

        BudgetSummary summary = new BudgetSummary();
        summary.setBudgetLevel(state.budgetLabel());
        summary.setCurrency("INR");
        summary.setLineItems(items);
        summary.setEstimatedCost(total);
        summary.setRemaining(remaining);
        summary.setWithinBudget(within);
        if (ceiling == null) {
            summary.setAssessment("No numeric ceiling was provided; estimated spend is ₹" + total + ".");
        } else if (within) {
            summary.setAssessment("Under budget. Ceiling ₹" + ceiling + ", estimated ₹" + total + ", remaining ₹" + remaining + ".");
        } else {
            summary.setAssessment("Over budget by ₹" + remaining.abs() + ". Re-planner should pick a cheaper hotel and fare.");
        }
        if (!domestic) {
            summary.setAssessment(summary.getAssessment()
                    + " Amounts are INR estimates for an India-origin traveler; local currency at destination is "
                    + localCurrency + ".");
        }
        return summary;
    }

    @Tool(description = "Estimate INR trip costs as Flight/Hotel/Food/Transport/Activities line items against a budget ceiling.")
    public String estimateInrBudget(
            @ToolParam(description = "Destination city or country") String destination,
            @ToolParam(description = "Number of travelers") int travelers,
            @ToolParam(description = "Number of nights") int nights,
            @ToolParam(description = "Budget ceiling in INR, e.g. 200000 or 2 lakh") String budgetCeiling,
            @ToolParam(description = "Travel style: balanced, budget, luxury", required = false) String travelStyle) {
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put(TravelState.DESTINATION, destination == null ? "" : destination);
        input.put(TravelState.TRAVELERS, Math.max(travelers, 1));
        input.put(TravelState.DEPARTURE_DATE, java.time.LocalDate.now());
        input.put(TravelState.RETURN_DATE, java.time.LocalDate.now().plusDays(Math.max(nights, 1)));
        BigDecimal parsed = TravelState.parseBudget(budgetCeiling);
        input.put(TravelState.BUDGET, parsed == null ? TravelState.UNSET_BUDGET : parsed);
        input.put(TravelState.BUDGET_LABEL, budgetCeiling == null || budgetCeiling.isBlank() ? "medium" : budgetCeiling);
        input.put(TravelState.TRAVEL_STYLE, travelStyle == null || travelStyle.isBlank() ? "balanced" : travelStyle);
        input.put(TravelState.COST_FACTOR, BigDecimal.ONE);
        input.put(TravelState.RETRY_COUNT, 0);
        input.put(TravelState.REPLAN_NOTES, "");
        return calculate(new TravelState(input)).toDisplay();
    }

    private String style(TravelState state) {
        return (state.travelStyle() + " " + state.replanNotes()).toLowerCase(Locale.ROOT);
    }

    private BigDecimal unit(long value) {
        return BigDecimal.valueOf(value);
    }
}
