package com.example.travel.graph;

import com.example.travel.dto.TravelRequest;
import com.example.travel.model.AgentDecision;
import com.example.travel.model.AgentPlan;
import com.example.travel.model.AgentStep;
import com.example.travel.model.BudgetSummary;
import com.example.travel.model.FlightOption;
import com.example.travel.model.HotelOption;
import com.example.travel.model.Itinerary;
import com.example.travel.model.ModificationRequest;
import com.example.travel.model.NodeFailureInfo;
import com.example.travel.model.PlanQualityScore;
import com.example.travel.model.ProvenanceEvent;
import com.example.travel.model.ReplanStrategy;
import com.example.travel.model.SemanticValidationResult;
import com.example.travel.model.SupervisorAssessment;
import com.example.travel.model.TravelAttraction;
import com.example.travel.model.TravelResearch;
import com.example.travel.model.TripRequirements;
import com.example.travel.model.WeatherForecast;
import com.example.travel.support.TripSlotHeuristics;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.travel.graph.TravelStateKeys.Rag;

@Slf4j
public class TravelState extends AgentState {

    /** Sentinel for "no numeric budget ceiling" — LangGraph schema defaults cannot be null. */
    public static final BigDecimal UNSET_BUDGET = new BigDecimal("-1");

    // Re-export grouped keys for backward compatibility (see TravelStateKeys).
    public static final String USER_REQUEST = TravelStateKeys.Request.USER_REQUEST;
    public static final String USER_ID = TravelStateKeys.Request.USER_ID;
    public static final String SELECTED_MODEL = TravelStateKeys.Request.SELECTED_MODEL;
    public static final String HISTORY_CONTEXT = TravelStateKeys.Request.HISTORY_CONTEXT;
    public static final String HISTORY_RESULT = TravelStateKeys.Request.HISTORY_RESULT;
    public static final String HISTORY_SELECTION = TravelStateKeys.Request.HISTORY_SELECTION;
    public static final String ORIGIN = TravelStateKeys.Trip.ORIGIN;
    public static final String DESTINATION = TravelStateKeys.Trip.DESTINATION;
    public static final String DEPARTURE_DATE = TravelStateKeys.Trip.DEPARTURE_DATE;
    public static final String RETURN_DATE = TravelStateKeys.Trip.RETURN_DATE;
    public static final String TRAVELERS = TravelStateKeys.Trip.TRAVELERS;
    public static final String DATES_FLEXIBLE = TravelStateKeys.Trip.DATES_FLEXIBLE;
    public static final String ROUND_TRIP = TravelStateKeys.Trip.ROUND_TRIP;
    public static final String BUDGET = TravelStateKeys.Budget.BUDGET;
    public static final String BUDGET_LABEL = TravelStateKeys.Budget.BUDGET_LABEL;
    public static final String TRAVEL_STYLE = TravelStateKeys.Preferences.TRAVEL_STYLE;
    public static final String ORIGIN_IATA = TravelStateKeys.Trip.ORIGIN_IATA;
    public static final String DESTINATION_IATA = TravelStateKeys.Trip.DESTINATION_IATA;
    public static final String FLIGHTS = TravelStateKeys.Results.FLIGHTS;
    public static final String HOTELS = TravelStateKeys.Results.HOTELS;
    public static final String ATTRACTIONS = TravelStateKeys.Results.ATTRACTIONS;
    public static final String RESEARCH = TravelStateKeys.Results.RESEARCH;
    public static final String ITINERARY = TravelStateKeys.Results.ITINERARY;
    public static final String BUDGET_SUMMARY = TravelStateKeys.Budget.BUDGET_SUMMARY;
    public static final String VALIDATION_ERRORS = TravelStateKeys.Validation.VALIDATION_ERRORS;
    public static final String RETRY_COUNT = TravelStateKeys.Control.RETRY_COUNT;
    public static final String MAX_RETRIES = TravelStateKeys.Control.MAX_RETRIES;
    public static final String FINAL_TIPS = TravelStateKeys.Results.FINAL_TIPS;
    /** @deprecated Use {@link #FINAL_TIPS}. */
    @Deprecated
    public static final String FINAL_PLAN = TravelStateKeys.Results.FINAL_PLAN;
    public static final String REPLAN_NOTES = TravelStateKeys.Planning.REPLAN_NOTES;
    public static final String COST_FACTOR = TravelStateKeys.Budget.COST_FACTOR;
    public static final String WEATHER = TravelStateKeys.Results.WEATHER;
    public static final String PIPELINE = TravelStateKeys.Control.PIPELINE;
    public static final String AWAITING_APPROVAL = TravelStateKeys.Hitl.AWAITING_APPROVAL;
    public static final String HITL_DECISION = TravelStateKeys.Hitl.HITL_DECISION;
    public static final String PREFERRED_AIRPORT = TravelStateKeys.Trip.PREFERRED_AIRPORT;
    public static final String CURRENCY = TravelStateKeys.Trip.CURRENCY;
    public static final String REQUEST_TYPE = TravelStateKeys.Request.REQUEST_TYPE;
    public static final String NEEDS_FLIGHTS = TravelStateKeys.Needs.NEEDS_FLIGHTS;
    public static final String NEEDS_HOTELS = TravelStateKeys.Needs.NEEDS_HOTELS;
    public static final String NEEDS_RESEARCH = TravelStateKeys.Needs.NEEDS_RESEARCH;
    public static final String NEEDS_WEATHER = TravelStateKeys.Needs.NEEDS_WEATHER;
    public static final String NEEDS_BUDGET = TravelStateKeys.Needs.NEEDS_BUDGET;
    public static final String NEEDS_ITINERARY = TravelStateKeys.Needs.NEEDS_ITINERARY;
    public static final String NEEDS_KNOWLEDGE = TravelStateKeys.Needs.NEEDS_KNOWLEDGE;
    public static final String NEEDS_HISTORY = TravelStateKeys.Needs.NEEDS_HISTORY;
    public static final String RUN_FLIGHTS = TravelStateKeys.Run.RUN_FLIGHTS;
    public static final String RUN_HOTELS = TravelStateKeys.Run.RUN_HOTELS;
    public static final String RUN_RESEARCH = TravelStateKeys.Run.RUN_RESEARCH;
    public static final String RUN_WEATHER = TravelStateKeys.Run.RUN_WEATHER;
    public static final String RUN_BUDGET = TravelStateKeys.Run.RUN_BUDGET;
    public static final String RUN_ITINERARY = TravelStateKeys.Run.RUN_ITINERARY;
    public static final String PLAN_STRATEGY = TravelStateKeys.Planning.PLAN_STRATEGY;
    public static final String PLAN_PRIORITY = TravelStateKeys.Planning.PLAN_PRIORITY;
    public static final String LAST_DECISION = TravelStateKeys.Control.LAST_DECISION;
    public static final String REPLAN_STRATEGY = TravelStateKeys.Planning.REPLAN_STRATEGY;
    public static final String SEMANTIC_NOTES = TravelStateKeys.Validation.SEMANTIC_NOTES;
    public static final String PROVENANCE = TravelStateKeys.Control.PROVENANCE;
    public static final String HOTEL_CHEAPER = TravelStateKeys.Preferences.HOTEL_CHEAPER;
    public static final String HOTEL_BUDGET = TravelStateKeys.Preferences.HOTEL_BUDGET;
    public static final String FLIGHT_PREFERENCE = TravelStateKeys.Preferences.FLIGHT_PREFERENCE;
    public static final String TRIP_REQUIREMENTS = TravelStateKeys.Preferences.TRIP_REQUIREMENTS;
    public static final String MODEL_POLICY = TravelStateKeys.Request.MODEL_POLICY;
    public static final String INTENT_CONFIDENCE = TravelStateKeys.Request.INTENT_CONFIDENCE;
    public static final String MODIFICATION = TravelStateKeys.Hitl.MODIFICATION;
    public static final String DISPATCH_ROUTE = TravelStateKeys.Control.DISPATCH_ROUTE;
    public static final String SUPERVISOR_DECISION = TravelStateKeys.Control.SUPERVISOR_DECISION;
    public static final String GRAPH_THREAD_ID = TravelStateKeys.Request.GRAPH_THREAD_ID;
    public static final String AGENT_PLAN = TravelStateKeys.Request.AGENT_PLAN;
    public static final String GOAL_EVALUATION = TravelStateKeys.Request.GOAL_EVALUATION;
    public static final String PLAN_VERSION = TravelStateKeys.Request.PLAN_VERSION;
    public static final String PLAN_QUALITY = TravelStateKeys.Validation.PLAN_QUALITY;
    public static final String SEMANTIC_VALIDATION = TravelStateKeys.Validation.SEMANTIC_VALIDATION;
    public static final String SUPERVISOR_ASSESSMENT = TravelStateKeys.Control.SUPERVISOR_ASSESSMENT;
    public static final String NODE_FAILURE = TravelStateKeys.Control.NODE_FAILURE;
    public static final String RETRY_TASK = TravelStateKeys.Control.RETRY_TASK;
    public static final String USER_INPUT_REQUIRED = TravelStateKeys.Control.USER_INPUT_REQUIRED;
    public static final String USER_INPUT_QUESTION = TravelStateKeys.Control.USER_INPUT_QUESTION;
    public static final String HOTEL_FALLBACK_EXHAUSTED = TravelStateKeys.Control.HOTEL_FALLBACK_EXHAUSTED;
    public static final String RAG_ENABLED = Rag.RAG_ENABLED;
    public static final String RAG_DECISION = Rag.RAG_DECISION;
    public static final String RAG_QUERY = Rag.RAG_QUERY;
    public static final String RAG_CONTEXT = Rag.RAG_CONTEXT;
    public static final String RAG_ANSWER = Rag.RAG_ANSWER;
    public static final String RAG_SOURCES = Rag.RAG_SOURCES;
    public static final String RAG_ITERATIONS = Rag.RAG_ITERATIONS;
    public static final String RAG_SUFFICIENT = Rag.RAG_SUFFICIENT;
    public static final String RAG_RETRIEVAL_METHOD = Rag.RAG_RETRIEVAL_METHOD;
    public static final String RAG_CANDIDATE_COUNT = Rag.RAG_CANDIDATE_COUNT;
    public static final String RAG_RERANKED_COUNT = Rag.RAG_RERANKED_COUNT;
    public static final String RAG_CONTEXT_CHARS = Rag.RAG_CONTEXT_CHARS;
    public static final String RAG_EVIDENCE_SCORE = Rag.RAG_EVIDENCE_SCORE;
    public static final String RAG_GROUNDEDNESS = Rag.RAG_GROUNDEDNESS;
    public static final String RAG_JUDGE_PASS = Rag.RAG_JUDGE_PASS;
    public static final String RAG_JUDGE_REASON = Rag.RAG_JUDGE_REASON;
    public static final String RAG_DESTINATION = Rag.RAG_DESTINATION;
    public static final String RAG_COUNTRY = Rag.RAG_COUNTRY;
    public static final String RAG_TOPICS = Rag.RAG_TOPICS;

    public static final Map<String, Channel<?>> SCHEMA = TravelStateSchema.SCHEMA;

    public TravelState(Map<String, Object> initData) {
        super(initData);
    }

    public static Map<String, Object> fromRequest(TravelRequest request, String historyContext) {
        LocalDate today = LocalDate.now();
        // Never invent a travel objective when the API request contains no prompt.
        // An empty turn is classified as GENERAL and can be clarified safely.
        String prompt = firstNonBlank(request.getOriginalPrompt(), request.getPrompt(), request.getPreferences(), "");
        boolean datesFlexible = isBlank(request.getDepartureDate()) && isBlank(request.getReturnDate())
                && !containsRelativeDate(prompt);
        boolean roundTrip = !containsOneWayIntent(prompt);
        int travelers = defaultInt(request.getAdults(), 1) + defaultInt(request.getChildren(), 0);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put(USER_REQUEST, prompt);
        input.put(USER_ID, firstNonBlank(request.getUserId(), ""));
        input.put(SELECTED_MODEL, blankToEmpty(request.getSelectedModel()));
        input.put(HISTORY_CONTEXT, historyContext == null ? "" : historyContext);
        input.put(HISTORY_RESULT, "");
        input.put(HISTORY_SELECTION, "APPROVED_RECENT");
        // Seed route slots from the CURRENT prompt before the graph starts.
        // API clients often send only prompt/preferences and leave DTO slots blank.
        // Every downstream specialist must see the same deterministic route; never
        // let an LLM/tool-selection pass turn a known destination into an empty slot.
        // CURRENT-TURN explicit route hints always win over hydrated conversation
        // values. This is critical for follow-ups such as:
        //   Previous: Bengaluru trip
        //   Current:  "any flights available from Hyderabad for today"
        // The conversation provides the destination (Bengaluru), while the
        // current prompt explicitly changes the origin to Hyderabad.
        String promptOriginHint = TripSlotHeuristics.extractOriginHint(prompt);
        String promptDestinationHint = TripSlotHeuristics.extractDestinationHint(prompt);
        String requestedOrigin = firstNonBlank(
                promptOriginHint,
                request.getDepartureCity());
        String requestedDestination = firstNonBlank(
                promptDestinationHint,
                request.getDestination());
        input.put(ORIGIN, TripSlotHeuristics.normalizePlace(requestedOrigin));
        input.put(DESTINATION, TripSlotHeuristics.normalizePlace(requestedDestination));
        // Relative dates belong to the CURRENT turn and must never be inherited
        // from conversation memory (e.g. an old trip on 2026-09-19 followed by
        // "show flights for today" on 2026-09-20).
        LocalDate departureDate;
        if (containsRelativeDate(prompt)) {
            departureDate = resolveRelativeDepartureDate(prompt, today);
        } else {
            departureDate = parseDate(request.getDepartureDate(), today);
        }
        LocalDate fallbackReturnDate = departureDate.plusDays(5);
        LocalDate requestedReturnDate;
        if (containsRelativeDate(prompt)) {
            requestedReturnDate = parseDate(request.getReturnDate(), fallbackReturnDate);
            requestedReturnDate = TripSlotHeuristics.inferReturnDate(prompt, departureDate, requestedReturnDate);
        } else {
            requestedReturnDate = parseDate(request.getReturnDate(), fallbackReturnDate);
        }
        if (TripSlotHeuristics.hasDurationHint(prompt)) {
            requestedReturnDate = TripSlotHeuristics.inferReturnDate(prompt, departureDate, requestedReturnDate);
        }

        // Never allow a stale date supplied by the UI/request hydration layer to
        // reach live providers. A historical date is valid only when the CURRENT
        // turn explicitly asks for that calendar date (the server will then
        // reject genuinely past searches). Otherwise, treat it as stale context
        // and use today for a live flight search.
        boolean currentTurnHasDateHint = TripSlotHeuristics.hasDateHint(prompt)
                || TripSlotHeuristics.hasDurationHint(prompt);
        if (departureDate.isBefore(today) && !currentTurnHasDateHint) {
            departureDate = today;
            requestedReturnDate = TripSlotHeuristics.inferReturnDate(prompt, departureDate, requestedReturnDate);
        }
        if (requestedReturnDate.isBefore(departureDate) && !currentTurnHasDateHint) {
            requestedReturnDate = departureDate.plusDays(5);
        }

        input.put(DEPARTURE_DATE, departureDate);
        input.put(RETURN_DATE, requestedReturnDate);
        input.put(DATES_FLEXIBLE, datesFlexible);
        input.put(ROUND_TRIP, roundTrip);
        input.put(TRAVELERS, Math.max(travelers, 1));
        String currentTurnBudget = TripSlotHeuristics.extractBudgetLabel(prompt);
        BigDecimal budget = parseBudget(firstNonBlank(currentTurnBudget, request.getBudget()));
        input.put(BUDGET, budget == null ? UNSET_BUDGET : budget);
        input.put(BUDGET_LABEL, firstNonBlank(currentTurnBudget, request.getBudget(), "medium"));
        input.put(TRAVEL_STYLE, firstNonBlank(request.getTravelStyle(), "balanced"));
        input.put(RETRY_COUNT, 0);
        input.put(GOAL_EVALUATION, new com.example.travel.model.GoalEvaluation());
        input.put(PLAN_VERSION, 1);
        input.put(MAX_RETRIES, 2);
        input.put(COST_FACTOR, BigDecimal.ONE);
        input.put(HOTEL_CHEAPER, Boolean.FALSE);
        input.put(HOTEL_BUDGET, UNSET_BUDGET);
        input.put(HOTEL_FALLBACK_EXHAUSTED, Boolean.FALSE);
        input.put(FLIGHT_PREFERENCE, "balanced");

        // Every routing flag is explicitly initialized. Missing flags must never
        // default to true because that can accidentally execute old specialists.
        input.put(NEEDS_FLIGHTS, false);
        input.put(NEEDS_HOTELS, false);
        input.put(NEEDS_RESEARCH, false);
        input.put(NEEDS_WEATHER, false);
        input.put(NEEDS_BUDGET, false);
        input.put(NEEDS_ITINERARY, false);
        input.put(NEEDS_KNOWLEDGE, false);
        input.put(NEEDS_HISTORY, false);
        input.put(RUN_FLIGHTS, false);
        input.put(RUN_HOTELS, false);
        input.put(RUN_RESEARCH, false);
        input.put(RUN_WEATHER, false);
        input.put(RUN_BUDGET, false);
        input.put(RUN_ITINERARY, false);
        input.put(MODEL_POLICY, com.example.travel.service.ModelRoutingContext.normalize(request.getSelectedModel()));
        input.put(VALIDATION_ERRORS, new ArrayList<String>());
        input.put(PIPELINE, new ArrayList<AgentStep>());
        input.put(AWAITING_APPROVAL, Boolean.FALSE);
        input.put(HITL_DECISION, "");
        input.put(USER_INPUT_REQUIRED, Boolean.FALSE);
        input.put(USER_INPUT_QUESTION, "");
        input.put(CURRENCY, "INR");
        input.put(ITINERARY, new Itinerary());
        input.put(BUDGET_SUMMARY, new BudgetSummary());
        input.put(WEATHER, new WeatherForecast("", "", false));
        input.put(RAG_ENABLED, Boolean.TRUE);
        input.put(RAG_DECISION, "skip");
        input.put(RAG_QUERY, "");
        input.put(RAG_CONTEXT, "");
        input.put(RAG_ANSWER, "");
        input.put(RAG_SOURCES, new ArrayList<String>());
        input.put(RAG_ITERATIONS, 0);
        input.put(RAG_SUFFICIENT, Boolean.FALSE);
        input.put(RAG_RETRIEVAL_METHOD, "none");
        input.put(RAG_CANDIDATE_COUNT, 0);
        input.put(RAG_RERANKED_COUNT, 0);
        input.put(RAG_CONTEXT_CHARS, 0);
        input.put(RAG_EVIDENCE_SCORE, 0.0d);
        input.put(RAG_GROUNDEDNESS, 0.0d);
        input.put(RAG_JUDGE_PASS, Boolean.TRUE);
        input.put(RAG_JUDGE_REASON, "not_applicable");
        input.put(RAG_DESTINATION, "");
        input.put(RAG_COUNTRY, "");
        input.put(RAG_TOPICS, new ArrayList<String>());
        return input;
    }

    public boolean ragEnabled() {
        return Boolean.TRUE.equals(this.<Boolean>value(RAG_ENABLED).orElse(Boolean.FALSE));
    }

    public String ragDecision() {
        return this.<String>value(RAG_DECISION).orElse("skip");
    }

    public String ragQuery() {
        return this.<String>value(RAG_QUERY).orElse("");
    }

    public String ragContext() {
        return this.<String>value(RAG_CONTEXT).orElse("");
    }

    public String ragAnswer() {
        return this.<String>value(RAG_ANSWER).orElse("");
    }

    public List<String> ragSources() {
        return this.<List<String>>value(RAG_SOURCES).orElseGet(List::of);
    }

    public int ragIterations() {
        Object value = this.value(RAG_ITERATIONS).orElse(0);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public boolean ragSufficient() {
        return Boolean.TRUE.equals(this.<Boolean>value(RAG_SUFFICIENT).orElse(Boolean.FALSE));
    }

    public String ragRetrievalMethod() {
        return this.<String>value(RAG_RETRIEVAL_METHOD).orElse("none");
    }

    public int ragCandidateCount() {
        Object value = this.value(RAG_CANDIDATE_COUNT).orElse(0);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public int ragRerankedCount() {
        Object value = this.value(RAG_RERANKED_COUNT).orElse(0);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public double ragGroundedness() {
        Object value = this.value(RAG_GROUNDEDNESS).orElse(0.0d);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    public boolean ragJudgePass() {
        return Boolean.TRUE.equals(this.<Boolean>value(RAG_JUDGE_PASS).orElse(Boolean.TRUE));
    }

    public String ragJudgeReason() {
        return this.<String>value(RAG_JUDGE_REASON).orElse("not_applicable");
    }

    public double ragEvidenceScore() {
        Object value = this.value(RAG_EVIDENCE_SCORE).orElse(0.0d);
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    public int ragContextChars() {
        Object value = this.value(RAG_CONTEXT_CHARS).orElse(0);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public String ragDestination() { return this.<String>value(RAG_DESTINATION).orElse(""); }

    public String ragCountry() { return this.<String>value(RAG_COUNTRY).orElse(""); }

    public List<String> ragTopics() { return this.<List<String>>value(RAG_TOPICS).orElseGet(List::of); }

    public String userRequest() {
        return this.<String>value(USER_REQUEST).orElse("");
    }

    public String userId() {
        return this.<String>value(USER_ID).orElse("");
    }

    public String selectedModel() {
        return this.<String>value(SELECTED_MODEL).orElse("");
    }

    public String historyContext() {
        return this.<String>value(HISTORY_CONTEXT).orElse("");
    }

    public String origin() {
        return this.<String>value(ORIGIN).orElse("");
    }

    public String destination() {
        return this.<String>value(DESTINATION).orElse("");
    }

    public LocalDate departureDate() {
        return this.<LocalDate>value(DEPARTURE_DATE).orElse(LocalDate.now());
    }

    public LocalDate returnDate() {
        return this.<LocalDate>value(RETURN_DATE).orElse(LocalDate.now().plusDays(5));
    }

    public Integer travelers() {
        return this.<Integer>value(TRAVELERS).orElse(1);
    }

    public boolean datesFlexible() {
        return Boolean.TRUE.equals(this.<Boolean>value(DATES_FLEXIBLE).orElse(Boolean.FALSE));
    }

    public boolean roundTrip() {
        return Boolean.TRUE.equals(this.<Boolean>value(ROUND_TRIP).orElse(Boolean.TRUE));
    }

    public BigDecimal budget() {
        BigDecimal value = this.<BigDecimal>value(BUDGET).orElse(UNSET_BUDGET);
        return UNSET_BUDGET.compareTo(value) == 0 ? null : value;
    }

    public String budgetLabel() {
        return this.<String>value(BUDGET_LABEL).orElse("medium");
    }

    public String travelStyle() {
        return this.<String>value(TRAVEL_STYLE).orElse("balanced");
    }

    public String originIata() {
        return this.<String>value(ORIGIN_IATA).orElse("");
    }

    public String destinationIata() {
        return this.<String>value(DESTINATION_IATA).orElse("");
    }

    public List<FlightOption> flights() {
        return this.<List<FlightOption>>value(FLIGHTS).orElseGet(List::of);
    }

    public List<HotelOption> hotels() {
        return this.<List<HotelOption>>value(HOTELS).orElseGet(List::of);
    }

    public List<TravelAttraction> attractions() {
        return this.<List<TravelAttraction>>value(ATTRACTIONS).orElseGet(List::of);
    }

    public List<TravelResearch> research() {
        return this.<List<TravelResearch>>value(RESEARCH).orElseGet(List::of);
    }

    public Itinerary itinerary() {
        Itinerary value = this.<Itinerary>value(ITINERARY).orElseGet(Itinerary::new);
        return value.isEmpty() ? null : value;
    }

    public BudgetSummary budgetSummary() {
        BudgetSummary value = this.<BudgetSummary>value(BUDGET_SUMMARY).orElseGet(BudgetSummary::new);
        return value.getEstimatedCost() == null ? null : value;
    }

    public List<String> validationErrors() {
        return this.<List<String>>value(VALIDATION_ERRORS).orElseGet(List::of);
    }

    public int retryCount() {
        Object value = this.value(RETRY_COUNT).orElse(0);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    public String finalTips() {
        String tips = this.<String>value(FINAL_TIPS).orElse("");
        if (!isBlank(tips)) {
            return tips;
        }
        return this.<String>value(FINAL_PLAN).orElse("");
    }

    /** @deprecated Use {@link #finalTips()}. */
    @Deprecated
    public String finalPlan() {
        return finalTips();
    }

    public int maxRetries() {
        Object value = this.value(MAX_RETRIES).orElse(2);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 2;
    }

    public String replanNotes() {
        return this.<String>value(REPLAN_NOTES).orElse("");
    }

    public String replanGuidance() {
        return replanNotes();
    }

    public BigDecimal costFactor() {
        Object value = this.value(COST_FACTOR).orElse(BigDecimal.ONE);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ONE;
    }

    public WeatherForecast weather() {
        WeatherForecast value = this.<WeatherForecast>value(WEATHER).orElseGet(() -> new WeatherForecast("", "", false));
        return (value.getLocation() == null || value.getLocation().isBlank()) && (value.getSummary() == null || value.getSummary().isBlank())
                ? null
                : value;
    }

    public List<AgentStep> pipeline() {
        return this.<List<AgentStep>>value(PIPELINE).orElseGet(List::of);
    }

    public boolean awaitingApproval() {
        return Boolean.TRUE.equals(this.<Boolean>value(AWAITING_APPROVAL).orElse(Boolean.FALSE));
    }

    /**
     * Returns whether this turn represents a user-facing trip plan that must
     * stop at the human approval boundary.  Do not rely only on REQUEST_TYPE:
     * the semantic planner may correctly construct a complete trip graph from
     * a natural request that was classified as MULTI_CAPABILITY.
     */
    public boolean isTripPlanningWorkflow() {
        if ("TRIP_PLANNING".equalsIgnoreCase(requestType())) return true;
        AgentPlan plan = agentPlan();
        if (plan != null && "TRIP_PLANNING".equalsIgnoreCase(plan.getGoal())) return true;

        if (plan == null || !plan.has("itinerary")) return false;

        // An itinerary-only request is informational and should not be forced
        // through approval. A composed trip plan is one where the itinerary is
        // tied to travel logistics/costs.
        return plan.has("budget")
                || (plan.has("flights") && plan.has("hotels"))
                || (plan.has("flights") && plan.has("hotels") && plan.has("research"));
    }

    public String hitlDecision() {
        return this.<String>value(HITL_DECISION).orElse("");
    }

    public String preferredAirport() {
        return this.<String>value(PREFERRED_AIRPORT).orElse("");
    }

    public String currency() {
        return this.<String>value(CURRENCY).orElse("INR");
    }

    public AgentPlan agentPlan() {
        return this.<AgentPlan>value(AGENT_PLAN).orElseGet(AgentPlan::new);
    }

    public com.example.travel.model.GoalEvaluation goalEvaluation() {
        return this.<com.example.travel.model.GoalEvaluation>value(GOAL_EVALUATION).orElseGet(com.example.travel.model.GoalEvaluation::new);
    }

    public String requestType() {
        return this.<String>value(REQUEST_TYPE).orElse("GENERAL");
    }

    /**
     * True when the planning graph is paused because a required piece of
     * information must be supplied by the user before execution can continue.
     * This is a persisted graph-state value, not an inferred frontend flag.
     */
    public boolean userInputRequired() {
        return Boolean.TRUE.equals(this.<Boolean>value(USER_INPUT_REQUIRED).orElse(Boolean.FALSE));
    }

    /**
     * Human-readable clarification question persisted with the graph checkpoint.
     */
    public String userInputQuestion() {
        return this.<String>value(USER_INPUT_QUESTION).orElse("");
    }

    public boolean needsHistory() {
        return flag(NEEDS_HISTORY);
    }

    public String historyResult() {
        return this.<String>value(HISTORY_RESULT).orElse("");
    }

    public String historySelection() {
        return this.<String>value(HISTORY_SELECTION).orElse("APPROVED_RECENT");
    }

    public boolean needsFlights() {
        return flag(NEEDS_FLIGHTS);
    }

    public boolean needsHotels() {
        return flag(NEEDS_HOTELS);
    }

    public boolean needsResearch() {
        return flag(NEEDS_RESEARCH);
    }

    public boolean needsWeather() {
        return flag(NEEDS_WEATHER);
    }

    public boolean needsBudget() {
        return flag(NEEDS_BUDGET);
    }

    public boolean needsItinerary() {
        return flag(NEEDS_ITINERARY);
    }

    public boolean needsKnowledge() {
        return flag(NEEDS_KNOWLEDGE);
    }

    public boolean runFlights() {
        return flag(RUN_FLIGHTS);
    }

    public boolean runHotels() {
        return flag(RUN_HOTELS);
    }

    public boolean runResearch() {
        return flag(RUN_RESEARCH);
    }

    public boolean runWeather() {
        return flag(RUN_WEATHER);
    }

    public boolean runBudget() {
        return flag(RUN_BUDGET);
    }

    public boolean runItinerary() {
        return flag(RUN_ITINERARY);
    }

    public static void applyIntentAndRun(Map<String, Object> updates, com.example.travel.model.IntentPlan intent) {
        if (intent == null) intent = new com.example.travel.model.IntentPlan();
        // AgentPlan is the sole normalization boundary. It owns the semantic
        // trip-planning contract; legacy flags below are only a projection.
        AgentPlan plan = AgentPlan.fromIntent(intent);
        updates.put(AGENT_PLAN, plan);
        updates.put(NEEDS_FLIGHTS, intent.isNeedsFlights());
        updates.put(NEEDS_HOTELS, intent.isNeedsHotels());
        updates.put(NEEDS_RESEARCH, intent.isNeedsResearch());
        updates.put(NEEDS_WEATHER, intent.isNeedsWeather());
        updates.put(NEEDS_BUDGET, intent.isNeedsBudget());
        updates.put(NEEDS_ITINERARY, intent.isNeedsItinerary());
        updates.put(NEEDS_KNOWLEDGE, intent.isNeedsKnowledge());
        updates.put(NEEDS_HISTORY, intent.isNeedsHistory());
        updates.put(RUN_FLIGHTS, intent.isNeedsFlights());
        updates.put(RUN_HOTELS, intent.isNeedsHotels());
        updates.put(RUN_RESEARCH, intent.isNeedsResearch());
        updates.put(RUN_WEATHER, intent.isNeedsWeather());
        updates.put(RUN_BUDGET, intent.isNeedsBudget());
        updates.put(RUN_ITINERARY, intent.isNeedsItinerary());
    }

    /** Keep the canonical AgentPlan synchronized with a selective replan pass. */
    public static void projectRunSelection(Map<String, Object> updates, TravelState state) {
        if (state == null || state.agentPlan() == null || state.agentPlan().getTasks().isEmpty()) return;
        AgentPlan plan = state.agentPlan();
        java.util.List<String> active = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(updates.getOrDefault(RUN_FLIGHTS, state.runFlights()))) active.add("flights");
        if (Boolean.TRUE.equals(updates.getOrDefault(RUN_HOTELS, state.runHotels()))) active.add("hotels");
        if (Boolean.TRUE.equals(updates.getOrDefault(RUN_RESEARCH, state.runResearch()))) active.add("research");
        if (Boolean.TRUE.equals(updates.getOrDefault(RUN_WEATHER, state.runWeather()))) active.add("weather");
        if (Boolean.TRUE.equals(updates.getOrDefault(RUN_BUDGET, state.runBudget()))) active.add("budget");
        if (Boolean.TRUE.equals(updates.getOrDefault(RUN_ITINERARY, state.runItinerary()))) active.add("itinerary");
        plan.selectForExecution(active);
        updates.put(AGENT_PLAN, plan);
    }

    /**
     * Canonical task guard used by graph nodes. Older checkpoints may not contain
     * an AgentPlan, so they fall back to their persisted RUN_* projection instead
     * of being silently skipped during checkpoint migration.
     */
    public boolean shouldExecuteTask(String taskId) {
        AgentPlan plan = agentPlan();
        if (plan != null && !plan.getTasks().isEmpty()) {
            return plan.shouldExecute(taskId);
        }
        return switch (taskId == null ? "" : taskId) {
            case "flights" -> runFlights();
            case "hotels" -> runHotels();
            case "research" -> runResearch();
            case "weather" -> runWeather();
            case "budget" -> runBudget();
            case "itinerary" -> runItinerary();
            default -> false;
        };
    }

    /** Whether flight results exist in state (independent of selective replan routing flags). */
    public boolean hasUsableFlights() {
        return flights().stream().anyMatch(flight ->
                flight != null && !"unavailable".equalsIgnoreCase(flight.getStatus()));
    }

    public boolean hasHotelResults() {
        return !hotels().isEmpty();
    }

    public boolean hasBudgetEstimate() {
        BudgetSummary budget = budgetSummary();
        return budget != null && budget.getEstimatedCost() != null && budget.getEstimatedCost().signum() > 0;
    }

    public boolean includeFlightsInReport() {
        return needsFlights() || hasUsableFlights();
    }

    public boolean includeHotelsInReport() {
        return needsHotels() || hasHotelResults();
    }

    public boolean includeBudgetInReport() {
        return needsBudget() || hasBudgetEstimate();
    }

    public boolean includeItineraryInReport() {
        Itinerary plan = itinerary();
        return needsItinerary() || (plan != null && !plan.isEmpty());
    }

    public boolean includeWeatherInReport() {
        WeatherForecast forecast = weather();
        return needsWeather() || (forecast != null
                && (!TravelState.isBlank(forecast.getSummary())
                    || (forecast.getDays() != null && !forecast.getDays().isEmpty())));
    }

    public String planStrategy() {
        return this.<String>value(PLAN_STRATEGY).orElse("parallel_search");
    }

    public String planPriority() {
        return this.<String>value(PLAN_PRIORITY).orElse("balanced");
    }

    public AgentDecision lastDecision() {
        return this.<AgentDecision>value(LAST_DECISION).orElseGet(AgentDecision::new);
    }

    public ReplanStrategy replanStrategy() {
        return this.<ReplanStrategy>value(REPLAN_STRATEGY).orElseGet(ReplanStrategy::new);
    }

    public List<String> semanticNotes() {
        return this.<List<String>>value(SEMANTIC_NOTES).orElseGet(List::of);
    }

    public List<ProvenanceEvent> provenance() {
        return this.<List<ProvenanceEvent>>value(PROVENANCE).orElseGet(List::of);
    }

    private boolean flag(String key) {
        return Boolean.TRUE.equals(this.<Boolean>value(key).orElse(Boolean.FALSE));
    }

    public long nights() {
        long days = ChronoUnit.DAYS.between(departureDate(), returnDate());
        return Math.max(1, days);
    }

    public boolean overBudget() {
        BudgetSummary summary = budgetSummary();
        BigDecimal ceiling = budget();
        return summary != null
                && summary.getEstimatedCost() != null
                && summary.getEstimatedCost().signum() > 0
                && ceiling != null
                && ceiling.compareTo(UNSET_BUDGET) != 0
                && !summary.isWithinBudget();
    }

    public boolean hotelCheaper() {
        return Boolean.TRUE.equals(this.<Boolean>value(HOTEL_CHEAPER).orElse(Boolean.FALSE));
    }

    /**
     * True means the hotel specialist has already attempted its independent
     * fallback and exhausted it for the current pass. An empty hotel result
     * is therefore a terminal provider outcome, not a reason to call MCP again.
     */
    public boolean hotelFallbackExhausted() {
        return Boolean.TRUE.equals(this.<Boolean>value(HOTEL_FALLBACK_EXHAUSTED).orElse(Boolean.FALSE));
    }

    public BigDecimal hotelBudget() {
        BigDecimal value = this.<BigDecimal>value(HOTEL_BUDGET).orElse(UNSET_BUDGET);
        return UNSET_BUDGET.compareTo(value) == 0 ? null : value;
    }

    public String flightPreference() {
        return this.<String>value(FLIGHT_PREFERENCE).orElse("balanced");
    }

    public String modelPolicy() {
        return this.<String>value(MODEL_POLICY).orElse("BALANCED");
    }

    public double intentConfidence() {
        Object value = this.value(INTENT_CONFIDENCE).orElse(1.0d);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 1.0d;
    }

    public ModificationRequest modification() {
        return this.<ModificationRequest>value(MODIFICATION).orElseGet(ModificationRequest::new);
    }

    public String supervisorDecision() {
        return this.<String>value(SUPERVISOR_DECISION).orElse("");
    }

    public String dispatchRoute() {
        return this.<String>value(DISPATCH_ROUTE).orElse("");
    }

    public String graphThreadId() {
        return this.<String>value(GRAPH_THREAD_ID).orElse("");
    }

    public PlanQualityScore planQuality() {
        return this.<PlanQualityScore>value(PLAN_QUALITY).orElseGet(PlanQualityScore::new);
    }

    public SemanticValidationResult semanticValidation() {
        return this.<SemanticValidationResult>value(SEMANTIC_VALIDATION).orElseGet(SemanticValidationResult::new);
    }

    public SupervisorAssessment supervisorAssessment() {
        return this.<SupervisorAssessment>value(SUPERVISOR_ASSESSMENT).orElseGet(SupervisorAssessment::new);
    }

    public TripRequirements tripRequirements() {
        return this.<TripRequirements>value(TRIP_REQUIREMENTS).orElseGet(TripRequirements::new);
    }

    public NodeFailureInfo nodeFailure() {
        return this.<NodeFailureInfo>value(NODE_FAILURE).orElseGet(NodeFailureInfo::new);
    }

    public String retryTask() {
        return this.<String>value(RETRY_TASK).orElse("");
    }

    public boolean shouldReplan() {
        boolean deterministicFailure = !validationErrors().isEmpty();
        boolean semanticFailure = !semanticNotes().isEmpty() || semanticValidation().failed();
        boolean semanticRepairAlreadyAttempted = semanticFailure
                && retryCount() > 0
                && replanNotes().contains("semanticRepairAttempt=true");
        boolean qualityFailure = planQuality() != null && planQuality().getOverall() > 0
                && planQuality().getOverall() < PlanQualityScore.PASS_THRESHOLD;
        boolean ragGroundingFailure = ragEnabled() && ragSufficient() && !ragJudgePass();

        // Semantic itinerary issues get one targeted repair pass only. If the
        // repaired itinerary still has the same preference warning, accept it
        // as a quality caveat instead of regenerating the itinerary again.
        if (semanticRepairAlreadyAttempted && !deterministicFailure
                && !qualityFailure && !ragGroundingFailure) {
            return false;
        }
        return (deterministicFailure || semanticFailure || qualityFailure || ragGroundingFailure)
                && retryCount() < maxRetries();
    }

    public boolean shouldReplanForBudget() {
        return overBudget() && retryCount() < maxRetries();
    }

    private static boolean containsOneWayIntent(String request) {
        if (request == null || request.isBlank()) {
            return false;
        }
        String lower = request.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("one-way") || lower.contains("one way") || lower.contains("single journey");
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public static String blankToEmpty(String value) {
        return isBlank(value) ? "" : value.trim();
    }

    private static boolean containsRelativeDate(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String text = prompt.toLowerCase(java.util.Locale.ROOT);
        return text.matches(".*\\b(today|tomorrow|day after tomorrow)\\b.*");
    }

    private static LocalDate resolveRelativeDepartureDate(String prompt, LocalDate today) {
        String text = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
        if (text.matches(".*\\bday after tomorrow\\b.*")) return today.plusDays(2);
        if (text.matches(".*\\btomorrow\\b.*")) return today.plusDays(1);
        return today;
    }

    public static LocalDate parseDate(String value, LocalDate fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static BigDecimal parseBudget(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.toLowerCase().replace(",", "").replace("₹", "").replace("rs", "").trim();
        if (normalized.matches(".*\\d+(\\.\\d+)?\\s*lakh.*") || normalized.matches(".*\\d+(\\.\\d+)?\\s*lac.*")) {
            String number = normalized.replaceAll("[^0-9.]", "");
            return parseDecimal(number).multiply(BigDecimal.valueOf(100_000));
        }
        if (normalized.matches("\\d+(\\.\\d+)?\\s*l\\b") || normalized.endsWith("l")) {
            String number = normalized.replaceAll("[^0-9.]", "");
            return parseDecimal(number).multiply(BigDecimal.valueOf(100_000));
        }
        String digits = normalized.replaceAll("[^0-9.]", "");
        if (digits.isBlank()) {
            return null;
        }
        return parseDecimal(digits);
    }

    private static BigDecimal parseDecimal(String digits) {
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    public static Map<String, Object> trace(String node, String status, String detail) {
        return Map.of(PIPELINE, List.of(new AgentStep(node, AgentStep.normalizeStatus(status), detail)));
    }

    public static Map<String, Object> provenance(ProvenanceEvent event) {
        return Map.of(PROVENANCE, List.of(event));
    }

    private static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
