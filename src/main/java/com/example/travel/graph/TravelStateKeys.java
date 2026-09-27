package com.example.travel.graph;

/**
 * LangGraph state channel keys grouped by concern. {@link TravelState} re-exports these
 * for backward compatibility.
 */
public final class TravelStateKeys {

    private TravelStateKeys() {
    }

    public static final class Request {
        public static final String USER_REQUEST = "userRequest";
        public static final String USER_ID = "userId";
        public static final String SELECTED_MODEL = "selectedModel";
        public static final String HISTORY_CONTEXT = "historyContext";
        public static final String HISTORY_RESULT = "historyResult";
        public static final String HISTORY_SELECTION = "historySelection";
        public static final String REQUEST_TYPE = "requestType";
        public static final String INTENT_CONFIDENCE = "intentConfidence";
        public static final String MODEL_POLICY = "modelPolicy";
        public static final String GRAPH_THREAD_ID = "graphThreadId";
        public static final String AGENT_PLAN = "agentPlan";
        public static final String GOAL_EVALUATION = "goalEvaluation";
        public static final String PLAN_VERSION = "planVersion";

        private Request() {
        }
    }

    public static final class Trip {
        public static final String ORIGIN = "origin";
        public static final String DESTINATION = "destination";
        public static final String DEPARTURE_DATE = "departureDate";
        public static final String RETURN_DATE = "returnDate";
        public static final String TRAVELERS = "travelers";
        public static final String DATES_FLEXIBLE = "datesFlexible";
        public static final String ROUND_TRIP = "roundTrip";
        public static final String ORIGIN_IATA = "originIata";
        public static final String DESTINATION_IATA = "destinationIata";
        public static final String PREFERRED_AIRPORT = "preferredAirport";
        public static final String CURRENCY = "currency";

        private Trip() {
        }
    }

    public static final class Budget {
        public static final String BUDGET = "budget";
        public static final String BUDGET_LABEL = "budgetLabel";
        public static final String BUDGET_SUMMARY = "budgetSummary";
        public static final String COST_FACTOR = "costFactor";

        private Budget() {
        }
    }

    public static final class Preferences {
        public static final String TRAVEL_STYLE = "travelStyle";
        public static final String HOTEL_CHEAPER = "hotelCheaper";
        public static final String HOTEL_BUDGET = "hotelBudget";
        public static final String FLIGHT_PREFERENCE = "flightPreference";
        public static final String TRIP_REQUIREMENTS = "tripRequirements";

        private Preferences() {
        }
    }

    public static final class Results {
        public static final String FLIGHTS = "flights";
        public static final String HOTELS = "hotels";
        public static final String ATTRACTIONS = "attractions";
        public static final String RESEARCH = "research";
        public static final String ITINERARY = "itinerary";
        public static final String WEATHER = "weather";
        public static final String FINAL_TIPS = "finalTips";
        /** @deprecated Use {@link #FINAL_TIPS}; kept for checkpoint migration. */
        @Deprecated
        public static final String FINAL_PLAN = "finalPlan";

        private Results() {
        }
    }

    public static final class Needs {
        public static final String NEEDS_FLIGHTS = "needsFlights";
        public static final String NEEDS_HOTELS = "needsHotels";
        public static final String NEEDS_RESEARCH = "needsResearch";
        public static final String NEEDS_WEATHER = "needsWeather";
        public static final String NEEDS_BUDGET = "needsBudget";
        public static final String NEEDS_ITINERARY = "needsItinerary";
        public static final String NEEDS_KNOWLEDGE = "needsKnowledge";
        public static final String NEEDS_HISTORY = "needsHistory";

        private Needs() {
        }
    }

    /** Selective execution flags for the current graph pass (replan may narrow these). */
    public static final class Run {
        public static final String RUN_FLIGHTS = "runFlights";
        public static final String RUN_HOTELS = "runHotels";
        public static final String RUN_RESEARCH = "runResearch";
        public static final String RUN_WEATHER = "runWeather";
        public static final String RUN_BUDGET = "runBudget";
        public static final String RUN_ITINERARY = "runItinerary";

        private Run() {
        }
    }

    public static final class Planning {
        public static final String PLAN_STRATEGY = "planStrategy";
        public static final String PLAN_PRIORITY = "planPriority";
        public static final String REPLAN_NOTES = "replanNotes";
        public static final String REPLAN_STRATEGY = "replanStrategy";

        private Planning() {
        }
    }

    public static final class Validation {
        public static final String VALIDATION_ERRORS = "validationErrors";
        public static final String SEMANTIC_NOTES = "semanticNotes";
        public static final String SEMANTIC_VALIDATION = "semanticValidation";
        public static final String PLAN_QUALITY = "planQuality";

        private Validation() {
        }
    }


    public static final class Rag {
        public static final String RAG_ENABLED = "ragEnabled";
        public static final String RAG_DECISION = "ragDecision";
        public static final String RAG_QUERY = "ragQuery";
        public static final String RAG_CONTEXT = "ragContext";
        public static final String RAG_ANSWER = "ragAnswer";
        public static final String RAG_SOURCES = "ragSources";
        public static final String RAG_ITERATIONS = "ragIterations";
        public static final String RAG_SUFFICIENT = "ragSufficient";
        public static final String RAG_RETRIEVAL_METHOD = "ragRetrievalMethod";
        public static final String RAG_CANDIDATE_COUNT = "ragCandidateCount";
        public static final String RAG_RERANKED_COUNT = "ragRerankedCount";
        public static final String RAG_CONTEXT_CHARS = "ragContextChars";
        public static final String RAG_EVIDENCE_SCORE = "ragEvidenceScore";
        public static final String RAG_GROUNDEDNESS = "ragGroundedness";
        public static final String RAG_JUDGE_PASS = "ragJudgePass";
        public static final String RAG_JUDGE_REASON = "ragJudgeReason";
        public static final String RAG_DESTINATION = "ragDestination";
        public static final String RAG_COUNTRY = "ragCountry";
        public static final String RAG_TOPICS = "ragTopics";

        private Rag() {
        }
    }

    public static final class Control {
        /** True when the hotel specialist already exhausted its independent fallback for this pass. */
        public static final String HOTEL_FALLBACK_EXHAUSTED = "hotelFallbackExhausted";
        public static final String RETRY_COUNT = "retryCount";
        public static final String MAX_RETRIES = "maxRetries";
        public static final String PIPELINE = "pipeline";
        public static final String PROVENANCE = "provenance";
        public static final String LAST_DECISION = "lastDecision";
        public static final String DISPATCH_ROUTE = "dispatchRoute";
        public static final String SUPERVISOR_DECISION = "supervisorDecision";
        public static final String SUPERVISOR_ASSESSMENT = "supervisorAssessment";
        public static final String NODE_FAILURE = "nodeFailure";
        /** User explicitly requested a manual recovery of one failed task. */
        public static final String RETRY_TASK = "retryTask";
        public static final String USER_INPUT_REQUIRED = "userInputRequired";
        public static final String USER_INPUT_QUESTION = "userInputQuestion";

        private Control() {
        }
    }

    public static final class Hitl {
        public static final String AWAITING_APPROVAL = "awaitingApproval";
        public static final String HITL_DECISION = "hitlDecision";
        public static final String MODIFICATION = "modification";

        private Hitl() {
        }
    }
}
