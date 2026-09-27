package com.example.travel.graph;

public final class TravelGraphNodes {

    public static final String PLANNER = "planner";
    public static final String AIRPORT = "airport";
    public static final String FLIGHT = "flight";
    public static final String RESEARCH = "research";
    public static final String HOTEL = "hotel";
    public static final String BUDGET = "budget";
    public static final String ITINERARY = "itinerary";
    public static final String VALIDATOR = "validator";
    public static final String REPLAN = "replan";
    public static final String FINAL = "final";
    /** Human-in-the-loop gate; graph interrupts before this node. */
    public static final String HITL = "hitl";
    public static final String COMPLETE = "complete";
    public static final String INTENT = "intent";
    public static final String WEATHER = "weather";
    public static final String CANCEL = "cancel";
    public static final String ROUTER = "router";
    public static final String RAG = "rag";
    public static final String HISTORY = "history";
    public static final String FAN_OUT = "fan_out";
    public static final String SUPERVISOR = "supervisor";

    public static final String ROUTE_VALID = "valid";
    public static final String ROUTE_INVALID = "invalid";
    public static final String ROUTE_UNDER = "under";
    public static final String ROUTE_OVER = "over";
    public static final String ROUTE_SKIP_ITINERARY = "skip_itinerary";
    public static final String ROUTE_APPROVE = "approve";
    public static final String ROUTE_MODIFY = "modify";
    public static final String ROUTE_REJECT = "reject";
    public static final String ROUTE_PROCEED = "proceed";
    public static final String ROUTE_RETRY = "retry";

    private TravelGraphNodes() {
    }
}
