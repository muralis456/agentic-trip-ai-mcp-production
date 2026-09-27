package com.example.travel.tool;

/**
 * Documents scoped tool access. Agents receive only the tools listed here —
 * never a global toolbox.
 */
public final class ToolRegistry {

    public static final String FLIGHT_AGENT = "AirportLookup, FlightSearch";
    public static final String RESEARCH_AGENT = "Tavily, Weather";
    public static final String HOTEL_AGENT = "HotelSearch (MCP)";
    public static final String BUDGET_AGENT = "Currency, BudgetCalculator";
    public static final String ITINERARY_AGENT = "none";

    private ToolRegistry() {
    }
}
