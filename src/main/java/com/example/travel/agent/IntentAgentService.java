package com.example.travel.agent;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.model.AgentDecision;
import com.example.travel.model.IntentPlan;
import com.example.travel.model.MemoryIntentDecision;
import com.example.travel.service.RoutedLlm;
import com.example.travel.service.GraphProgressHub;
import com.example.travel.support.JsonSupport;
import com.example.travel.support.TripRequirementsParser;
import com.example.travel.support.IntentCapabilitySafetyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class IntentAgentService {

    private static final Logger log =
            LoggerFactory.getLogger(IntentAgentService.class);

    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;
    private final SemanticMemoryArbiter semanticMemoryArbiter;
    private final GraphProgressHub progressHub;

    public IntentAgentService(
            RoutedLlm routedLlm,
            JsonSupport jsonSupport,
            SemanticMemoryArbiter semanticMemoryArbiter,
            GraphProgressHub progressHub) {

        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
        this.semanticMemoryArbiter = semanticMemoryArbiter;
        this.progressHub = progressHub;
    }

    /**
     * Classifies the initial user request.
     *
     * The deterministic classifier is used first.
     * If confidence is below the configured threshold,
     * the LLM is used to refine the intent.
     */
    public Map<String, Object> classify(TravelState state) {
        IntentPlan plan = classifySemantically(state);

        log.info(
                "Intent agent requestType={} confidence={} capabilities=[flights={},hotels={},research={},weather={},budget={},itinerary={},knowledge={}] query={}",
                plan.getRequestType(), plan.getConfidence(),
                plan.isNeedsFlights(), plan.isNeedsHotels(), plan.isNeedsResearch(),
                plan.isNeedsWeather(), plan.isNeedsBudget(), plan.isNeedsItinerary(),
                plan.isNeedsKnowledge(), state == null ? "" : state.userRequest());

        return toUpdates(state, plan);
    }

    /**
     * Semantic-first intent analysis. There is deliberately no keyword
     * classifier in the decision path. The model interprets the user's
     * meaning, including natural language noise, typos, implicit objectives
     * and multiple simultaneous requests. Java only applies safety rules after
     * the model response: no invented capabilities and no invented intent.
     */
    /**
     * Production semantic intent pipeline.
     *
     * IMPORTANT: this method intentionally contains NO lexical/regex intent
     * detection. User language is interpreted by the semantic model. Java only
     * validates the returned structure and derives routing metadata from the
     * capability vector. This prevents every new phrasing from becoming another
     * keyword patch.
     */
    private IntentPlan classifySemantically(TravelState state) {
        if (state == null || TravelState.isBlank(state.userRequest())) {
            return emptyPlan();
        }

        String request = state.userRequest();
        emitActivity(state, "Reading your request and identifying the goal");
        IntentPlan primary = runSemanticIntentPass(request, false, state.historyContext());
        emitActivity(state, "Validating the requested capabilities");
        primary = sanitizeSemanticPlan(primary);

        log.info("Intent primary semantic result request={} type={} confidence={} capabilities={}",
                request, primary.getRequestType(), primary.getConfidence(), capabilitySummary(primary));

        // Memory retrieval is a separate semantic concern from travel planning.
        // Do not invoke another LLM on every ordinary travel request: with local
        // models this pass can add tens of seconds and can starve the executor.
        // Run it when the primary result is uncertain, already indicates history,
        // or looks like a possible history-vs-itinerary conflict. This remains
        // semantic-only; no keyword/regex detection is introduced.
        boolean memoryCheckNeeded = primary.isNeedsHistory()
                || (!hasAnyCapability(primary) && primary.getConfidence() < 0.70d)
                || primary.isNeedsItinerary() && !primary.isNeedsFlights()
                    && !primary.isNeedsHotels() && !primary.isNeedsResearch()
                    && !primary.isNeedsWeather() && !primary.isNeedsBudget();
        MemoryIntentDecision memoryIntent = memoryCheckNeeded
                ? runMemoryIntentPass(request, state.historyContext())
                : new MemoryIntentDecision(false, false, 0.0);
        log.info("Intent memory semantic result request={} history={} historyOnly={} selection={} confidence={}",
                request, memoryIntent.isNeedsHistory(), memoryIntent.isHistoryOnly(), memoryIntent.getSelection(), memoryIntent.getConfidence());

        // If the dedicated LLM memory pass is weak or conflicts with an itinerary
        // interpretation, obtain an independent semantic signal from the embedding
        // model. This is still meaning-based; no request text is inspected.
        if (memoryCheckNeeded && (!memoryIntent.isNeedsHistory() || memoryIntent.getConfidence() < 0.70d
                || (memoryIntent.isNeedsHistory() && !memoryIntent.isHistoryOnly() && primary.isNeedsItinerary()))) {
            MemoryIntentDecision semanticRecovery = semanticMemoryArbiter.recover(request);
            if (semanticRecovery.getConfidence() > memoryIntent.getConfidence()) {
                memoryIntent = semanticRecovery;
                log.info("Intent memory semantic recovery selected history={} historyOnly={} confidence={}",
                        memoryIntent.isNeedsHistory(), memoryIntent.isHistoryOnly(), memoryIntent.getConfidence());
            }
        }

        if (memoryIntent.isNeedsHistory() && memoryIntent.getConfidence() >= 0.70d) {
            if (memoryIntent.isHistoryOnly()) {
                IntentPlan historyPlan = emptyPlan();
                historyPlan.setNeedsHistory(true);
                historyPlan.setHistorySelection(normalizeHistorySelection(memoryIntent.getSelection()));
                historyPlan.setConfidence(memoryIntent.getConfidence());
                historyPlan.setStrategy("history_retrieval");
                historyPlan.setPriority("history");
                return finalizeSemanticPlan(request, historyPlan);
            }
            primary.setNeedsHistory(true);
            primary.setHistorySelection(normalizeHistorySelection(memoryIntent.getSelection()));
        }

        // A good semantic classification is authoritative. Do NOT let an
        // independent embedding classifier veto a valid multi-capability plan.
        // The previous architecture caused exactly this regression: the LLM
        // correctly detected flights/budget/itinerary, then embedding similarity
        // returned all false and the request became GENERAL.
        // A coherent capability vector is more useful than a low confidence
        // scalar from a small local model. Preserve a semantically populated
        // primary result instead of paying for another full extraction pass.
        if (hasAnyCapability(primary) && primary.getConfidence() >= 0.45) {
            return finalizeSemanticPlan(request, primary);
        }

        // If the first model is uncertain or returns an empty capability vector,
        // ask a second semantic pass to independently reconsider the same text.
        // This is still meaning-based and works for new wording, typos and
        // natural language that was never anticipated by Java code.
        IntentPlan adjudicated = runSemanticIntentPass(request, true, state.historyContext());
        adjudicated = sanitizeSemanticPlan(adjudicated);

        log.info("Intent secondary semantic result request={} type={} confidence={} capabilities={}",
                request, adjudicated.getRequestType(), adjudicated.getConfidence(), capabilitySummary(adjudicated));

        if (hasAnyCapability(adjudicated)
                && (!hasAnyCapability(primary) || adjudicated.getConfidence() >= primary.getConfidence())) {
            return finalizeSemanticPlan(request, adjudicated);
        }

        if (hasAnyCapability(primary)) {
            return finalizeSemanticPlan(request, primary);
        }

        // Embeddings remain an observability/recovery signal only. They are not
        // allowed to overwrite a semantic LLM decision or manufacture a set of
        // capabilities. If both semantic passes are genuinely uncertain, the
        // safe result is GENERAL rather than an invented specialist action.
        log.info("Intent semantic classification unresolved request={}; returning GENERAL", request);
        return finalizeSemanticPlan(request, emptyPlan());
    }

    private IntentPlan runSemanticIntentPass(String request, boolean adjudication, String historyContext) {
        String system = adjudication ? """
                You are the independent semantic adjudicator for a production travel-agent.

                Re-evaluate the USER REQUEST from its meaning, not literal words or
                predefined phrases. The wording may be novel, abbreviated, noisy,
                misspelled, conversational, or grammatically incomplete.

                Identify every capability the user is actually requesting NOW.
                Do not infer a capability merely because it would be useful.
                Do not use previous conversation state.

                Capability meanings:
                flights = airline/airfare/flight search or flight details.
                hotels = accommodation/lodging/rooms/stay options.
                research = recommendations, attractions, activities, destination research
                           or information that needs current external research.
                weather = current/forecast weather or weather-dependent conditions.
                budget = calculating, estimating, comparing, constraining or optimizing
                         the OVERALL trip cost/spend. A monetary ceiling attached to another
                         specialist (for example hotels under X, flights under X, or activities
                         under X) is a constraint for that specialist, NOT a budget capability.
                         Return its scope separately as HOTEL, FLIGHT, ACTIVITY or OTHER.
                itinerary = organizing a journey into a coherent schedule or day-by-day plan,
                            including a request to create or modify that schedule. Do NOT select
                            itinerary merely because the user says travel, trip, visit, or asks when
                            they want to travel; generic travel advice is not an itinerary.
                knowledge = durable/general travel guidance such as culture, customs, safety,
                            packing, visa guidance, local practical advice or overview. It can be
                            combined with a live capability when the user explicitly wants practical
                            travel guidance derived from that information; do not infer it just from
                            the destination or from a generic travel mention.
                history = retrieving, recalling, reopening or summarizing a previously saved
                          trip/conversation belonging to the current user. This is a memory/database
                          retrieval objective, not travel research and not a new trip plan.

                History examples (semantic examples, not literal triggers):
                - "show me the trip we planned most recently" => needsHistory=true, all travel
                  execution capabilities=false.
                - "what was my last vacation plan?" => needsHistory=true, all travel execution
                  capabilities=false.
                - "open my previous Paris plan" => needsHistory=true; any destination/entity
                  reference is a retrieval constraint, not a new trip-planning request.
                - "plan a new trip similar to my last one" => this is not history-only; preserve
                  the new planning capabilities and use memory as context if the workflow supports it.

                Key semantic rule: infer the user's objective, not the presence or absence
                of a particular word. Distinguish an overall cost-analysis objective from a
                price ceiling that merely constrains another specialist. For example, a person can clearly ask for a vacation
                schedule without using the word "itinerary", and can ask for cost limits
                without using the word "budget".

                Preserve multiple objectives when the user asks for them. Do not collapse
                a multi-objective request to one specialist.

                Return JSON only with this exact shape:
                {
                  "requestType":"",
                  "needsFlights":false,
                  "needsHotels":false,
                  "needsResearch":false,
                  "needsWeather":false,
                  "needsBudget":false,
                  "budgetScope":"NONE",
                  "needsItinerary":false,
                  "needsKnowledge":false,
                  "needsHistory":false,
                  "historySelection":"APPROVED_RECENT",
                  "strategy":"",
                  "priority":"",
                  "confidence":0.0
                }
                """ : """
                You are the semantic intent planner for a production travel-agent system.

                Understand the USER REQUEST by meaning. Do not classify by exact keywords,
                regex patterns, or a fixed list of trigger phrases. Users may express the
                same goal in completely different ways, use typos, abbreviations, speech-to-
                text errors, slang, or incomplete grammar.

                Determine every capability explicitly or semantically requested by the user.
                Do not activate capabilities merely because they might be useful.
                Do not copy capabilities from previous state.

                IMPORTANT CONVERSATIONAL FOLLOW-UP RULE:
                The CURRENT USER REQUEST may be a short continuation such as a request to
                continue, proceed, show it, open it, retry it, or otherwise act on the
                immediately preceding unresolved request. When that happens, use the supplied
                conversation context to resolve what the user is referring to. The context is
                not a new objective: it only resolves the referent of the current turn. If the
                immediately preceding meaningful user request was a saved-trip/history request
                and the current turn is a continuation, preserve that history objective instead
                of starting a new travel-planning task. Ignore assistant error messages as
                objectives; they describe execution outcome, not what the user wanted.

                Capability meanings:
                - flights: airline/airfare/flight search, options, availability or details
                - hotels: accommodation/lodging/rooms/stay options
                - research: recommendations, attractions, activities or current destination research
                - weather: current/forecast weather, temperature, precipitation or conditions.
                  Do NOT enable weather merely because the user mentions travelling to a destination
                  or asks for precautions. Precautions, safety, packing and practical travel advice
                  belong to knowledge unless live weather is explicitly requested.
                - budget: overall trip cost estimation, comparison, constraints or optimization. A price
                  ceiling scoped to another requested capability is not a budget capability.
                - itinerary: a coherent trip schedule, day-by-day journey plan, or schedule change.
                  Do NOT enable itinerary for generic travel advice or a precautions question; it
                  requires an actual schedule/planning objective.
                - knowledge: durable travel guidance such as culture, customs, safety, packing,
                  visa guidance, practical local advice or destination overview. It may be
                  requested alongside a live capability when the user is asking for the travel
                  implications of that information (for example weather for an upcoming visit,
                  or flight/hotel advice that explicitly asks for practical travel guidance).
                  Do not enable it merely because the request happens to mention a destination.
                - history: retrieve, recall, reopen or summarize a previously saved trip/conversation
                  from the current user's memory/database. This is a retrieval objective, not research
                  and not a new trip plan.

                Semantic principles:
                - Infer intent from the complete sentence and relationships between its parts.
                History examples (semantic examples, not literal triggers):
                - "show me the trip we planned most recently" => needsHistory=true and no live
                  specialist capability.
                - "what was my last vacation plan?" => needsHistory=true and no new planning.
                - "open my previous Paris plan" => history retrieval with Paris as a retrieval
                  constraint, not a new trip request.
                - "plan a new trip similar to my last one" => new trip planning; history may be
                  useful as context but the request is not history-only.

                - A route plus a duration plus a travel objective can express trip planning even
                  when the user never says "plan" or "itinerary".
                - A monetary constraint can express a budget objective even when the user never
                  says "budget", but only when the constraint applies to the overall trip.
                - A monetary constraint scoped to a requested specialist is that specialist
                  capability's input constraint, not a separate budget-agent request.
                  Example: "best hotels under 50k" => hotels=true, budget=false, budgetScope=HOTEL.
                - Multiple requested outcomes must remain multiple capabilities.
                - Do not add flights, hotels, weather, research or budget simply because a trip exists.
                - A monetary limit attached to another capability is an input constraint, not a
                  request for the Budget Agent. For example, "best hotels in Bengaluru under 50k"
                  means hotels=true, budget=false, budgetScope=HOTEL. "flights under 20k" means
                  flights=true, budget=false, budgetScope=FLIGHT. A request such as "what will the
                  whole trip cost" means budget=true, budgetScope=TRIP.
                - Do not confuse durable knowledge with live research.
                - If the request is genuinely ambiguous, return GENERAL with all capabilities false.

                Return JSON only:
                {
                  "requestType":"",
                  "needsFlights":false,
                  "needsHotels":false,
                  "needsResearch":false,
                  "needsWeather":false,
                  "needsBudget":false,
                  "budgetScope":"NONE",
                  "needsItinerary":false,
                  "needsKnowledge":false,
                  "needsHistory":false,
                  "historySelection":"APPROVED_RECENT",
                  "strategy":"",
                  "priority":"",
                  "confidence":0.0
                }
                """;

        try {
            String content = routedLlm.complete(
                    AgentRole.EXTRACT,
                    system,
                    "USER REQUEST:\n" + request + "\n\nCONVERSATION CONTEXT (use only to resolve conversational follow-ups; do not invent new objectives):\n" + boundedHistoryContext(historyContext) + "\n\nReturn the semantic capability plan now.");
            return jsonSupport.read(content, IntentPlan.class).orElseGet(this::emptyPlan);
        } catch (Exception ex) {
            if (ex instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Semantic intent pass failed adjudication={}", adjudication, ex);
            return emptyPlan();
        }
    }

    /**
     * Focused semantic gate for persistent-memory retrieval.
     *
     * This runs independently of the general capability classifier because
     * retrieval intent is orthogonal to travel-domain capabilities. A user
     * asking to recall a saved itinerary can otherwise be misclassified as a
     * brand-new itinerary request by a small local model.
     */
    private MemoryIntentDecision runMemoryIntentPass(String request, String historyContext) {
        if (TravelState.isBlank(request)) {
            return new MemoryIntentDecision(false, false, 0.0);
        }

        String system = """
                You are a semantic memory-intent classifier for a production
                travel assistant. Decide whether the user's CURRENT request
                asks to retrieve information that already exists in the user's
                persistent conversation/trip memory.

                Understand meaning, not literal words. The user may use any
                wording, abbreviations, typos, or conversational phrasing.
                Never use a keyword trigger.

                IMPORTANT CONVERSATIONAL FOLLOW-UP RULE:
                The current request can be a short continuation of the immediately preceding
                meaningful user request. Use the supplied conversation context to resolve the
                referent. For example, if the prior meaningful request asked for a saved trip
                and the current request asks to continue/proceed, classify the continuation as
                the same history retrieval objective. Do not treat the assistant's prior failure
                message as a new user objective.

                Set needsHistory=true when the user wants to recall, reopen,
                inspect, summarize, compare, or otherwise obtain a previously
                saved trip/conversation.

                Set historyOnly=true when the requested answer can be satisfied
                by retrieving prior user data and does NOT ask to create a new
                travel plan or execute a new live travel search.

                Also choose the semantic retrieval selection policy:
                - APPROVED_RECENT: most recently finalized/approved/confirmed saved trip.
                  Use this as the default for a request for the user's recent/last trip.
                - RECENT_ANY: most recently saved trip regardless of approval state.
                - PENDING: a draft/plan that is awaiting approval.
                - REJECTED: a previously rejected trip.
                - SPECIFIC: the user identifies a particular saved trip/destination/date.
                - CONVERSATION: the user wants a previous conversation rather than a saved trip.
                The selection is semantic. Do not classify it from literal trigger words alone.

                Set historyOnly=false when previous memory is only context for a
                new task, for example asking to create a new trip similar to an
                earlier trip.

                Also return a semantic selection value:
                APPROVED_RECENT = latest finalized/approved/confirmed trip (default for
                "my recent/last trip" style retrieval requests).
                RECENT_ANY = latest saved trip regardless of status.
                PENDING = trip awaiting approval.
                REJECTED = rejected trip.
                SPECIFIC = a particular prior trip identified by destination/date/entity.
                CONVERSATION = previous conversation/history rather than a saved trip.

                Examples are semantic guidance only:
                - a request for the user's most recently saved trip -> true/true
                - a request to show details from a previous vacation -> true/true
                - a request to reopen an earlier Paris itinerary -> true/true
                - a request to build a new itinerary based on the last trip -> true/false
                - a request for a completely new destination plan -> false/false

                Return JSON only:
                {
                  "needsHistory":false,
                  "historyOnly":false,
                  "selection":"APPROVED_RECENT",
                  "confidence":0.0
                }
                """;

        try {
            String content = routedLlm.complete(
                    AgentRole.EXTRACT,
                    system,
                    "CURRENT USER REQUEST:\n" + request + "\n\nRECENT CONVERSATION CONTEXT:\n" + boundedHistoryContext(historyContext) + "\n\nReturn the semantic memory decision now.");
            java.util.Optional<MemoryIntentDecision> parsed = jsonSupport.read(content, MemoryIntentDecision.class);
            MemoryIntentDecision decision = parsed.orElse(new MemoryIntentDecision(false, false, 0.0));
            if (decision.getConfidence() < 0) decision.setConfidence(0);
            if (decision.getConfidence() > 1) decision.setConfidence(1);
            decision.setSelection(normalizeHistorySelection(decision.getSelection()));
            return decision;
        } catch (Exception ex) {
            if (ex instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Semantic memory intent pass failed", ex);
            return new MemoryIntentDecision(false, false, 0.0);
        }
    }

    private String boundedHistoryContext(String historyContext) {
        if (historyContext == null || historyContext.isBlank()) return "(none)";
        int max = 7000;
        return historyContext.length() <= max ? historyContext : historyContext.substring(Math.max(0, historyContext.length() - max));
    }

    private String normalizeHistorySelection(String selection) {
        if (selection == null || selection.isBlank()) return "APPROVED_RECENT";
        String normalized = selection.trim().toUpperCase(java.util.Locale.ROOT);
        return java.util.Set.of("APPROVED_RECENT", "RECENT_ANY", "PENDING", "REJECTED", "SPECIFIC", "CONVERSATION")
                .contains(normalized) ? normalized : "APPROVED_RECENT";
    }

    private boolean hasAnyCapability(IntentPlan plan) {
        return plan != null && (plan.isNeedsFlights() || plan.isNeedsHotels()
                || plan.isNeedsResearch() || plan.isNeedsWeather()
                || plan.isNeedsBudget() || plan.isNeedsItinerary()
                || plan.isNeedsKnowledge() || plan.isNeedsHistory());
    }

    private IntentPlan finalizeSemanticPlan(String request, IntentPlan plan) {
        IntentPlan result = plan == null ? emptyPlan() : plan;

        // The semantic model is authoritative for meaning, while deterministic
        // heuristics only validate the structure of an implicit trip request.
        // Example: "From Bengaluru to Tokyo for 7 days under ₹2 lakh" is a
        // complete trip-planning contract even though it never says
        // "itinerary" or "plan". Do not make capability selection depend on
        // literal keywords.
        boolean implicitTripPlanning = isImplicitTripPlanning(request, result);
        if (implicitTripPlanning) {
            result.setRequestType(IntentPlan.TRIP_PLANNING);
        }

        boolean tripPlanning = IntentPlan.TRIP_PLANNING.equalsIgnoreCase(result.getRequestType());
        IntentCapabilitySafetyGuard.apply(request, result);
        if (tripPlanning) {
            result.setRequestType(IntentPlan.TRIP_PLANNING);
            result.setNeedsFlights(true);
            result.setNeedsHotels(true);
            result.setNeedsResearch(true);
            result.setNeedsWeather(true);
            result.setNeedsBudget(true);
            result.setNeedsItinerary(true);
            result.setNeedsKnowledge(true);
            result.setBudgetScope("TRIP");
            result.setStrategy("trip_planning");
            result.setPriority("balanced");
        }

        int count = (result.isNeedsFlights() ? 1 : 0)
                + (result.isNeedsHotels() ? 1 : 0)
                + (result.isNeedsResearch() ? 1 : 0)
                + (result.isNeedsWeather() ? 1 : 0)
                + (result.isNeedsBudget() ? 1 : 0)
                + (result.isNeedsItinerary() ? 1 : 0)
                + (result.isNeedsKnowledge() ? 1 : 0)
                + (result.isNeedsHistory() ? 1 : 0);

        if (count == 0) {
            result.setRequestType("GENERAL");
            result.setStrategy("none");
            result.setPriority("none");
            return result;
        }

        // Specialist-only requests remain selective; trip planning was normalized above.
        if (tripPlanning) {
            // already normalized
        } else if (count > 1) {
            result.setRequestType("MULTI_CAPABILITY");
        } else if (result.isNeedsFlights()) {
            result.setRequestType(IntentPlan.FLIGHT_SEARCH);
        } else if (result.isNeedsHotels()) {
            result.setRequestType(IntentPlan.HOTEL_SEARCH);
        } else if (result.isNeedsWeather()) {
            result.setRequestType(IntentPlan.WEATHER);
        } else if (result.isNeedsBudget()) {
            result.setRequestType("BUDGET");
        } else if (result.isNeedsResearch()) {
            result.setRequestType(IntentPlan.RESEARCH);
        } else if (result.isNeedsHistory()) {
            result.setRequestType("HISTORY");
        } else {
            result.setRequestType("TRAVEL_INFORMATION");
        }

        result.setStrategy(count > 1 ? "multi_capability" : result.getStrategy());
        if (result.getStrategy() == null || result.getStrategy().isBlank() || "none".equalsIgnoreCase(result.getStrategy())) {
            result.setStrategy(result.isNeedsItinerary() ? "trip_planning"
                    : result.isNeedsFlights() ? "flight_only"
                    : result.isNeedsHotels() ? "hotel_only"
                    : result.isNeedsWeather() ? "weather_only"
                    : result.isNeedsBudget() ? "budget_only"
                    : result.isNeedsResearch() ? "research_only"
                    : result.isNeedsHistory() ? "history_retrieval"
                    : "rag_only");
        }
        if (result.getPriority() == null || result.getPriority().isBlank() || "none".equalsIgnoreCase(result.getPriority())) {
            result.setPriority(result.isNeedsItinerary() ? "itinerary"
                    : result.isNeedsFlights() ? "flights"
                    : result.isNeedsHotels() ? "hotels"
                    : result.isNeedsWeather() ? "weather"
                    : result.isNeedsBudget() ? "budget"
                    : result.isNeedsResearch() ? "research"
                    : result.isNeedsHistory() ? "history"
                    : "knowledge");
        }
        return result;
    }

    private boolean isImplicitTripPlanning(String request, IntentPlan plan) {
        if (request == null || request.isBlank() || plan == null) return false;
        if (IntentPlan.TRIP_PLANNING.equalsIgnoreCase(plan.getRequestType())) return true;

        boolean route = com.example.travel.support.TripSlotHeuristics.hasRouteHint(request);
        boolean duration = com.example.travel.support.TripSlotHeuristics.hasDurationHint(request);
        if (!route || !duration) return false;

        // Route + duration + a trip-wide budget is a complete travel contract
        // even when the user never says "plan". Do not let a flight-only model
        // interpretation collapse such a request into FLIGHT_SEARCH.
        String lower = request.toLowerCase(java.util.Locale.ROOT);
        boolean budgetConstraint = lower.matches(".*(?:under|below|within|budget|\u20b9|rs\\.?|inr|usd|\\$|\\u20ac|\\u00a3)\\s*.*");
        if (budgetConstraint) return true;

        // Otherwise require another meaningful trip-planning capability. This
        // prevents ordinary round-trip flight/date requests from becoming full
        // itinerary workflows.
        return plan.isNeedsHotels()
                || plan.isNeedsResearch()
                || plan.isNeedsBudget()
                || plan.isNeedsItinerary()
                || plan.isNeedsKnowledge();
    }

    private IntentPlan sanitizeSemanticPlan(IntentPlan plan) {
        if (plan == null) return emptyPlan();
        if (plan.getConfidence() <= 0) plan.setConfidence(0.5);
        if (plan.getConfidence() > 1) plan.setConfidence(1);
        if (plan.getRequestType() == null || plan.getRequestType().isBlank()) {
            plan.setRequestType("GENERAL");
        }

        String scope = plan.getBudgetScope();
        if (scope == null || scope.isBlank()) scope = "NONE";
        scope = scope.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("NONE", "TRIP", "HOTEL", "FLIGHT", "ACTIVITY", "OTHER").contains(scope)) {
            scope = "NONE";
        }
        plan.setBudgetScope(scope);

        // If a model explicitly selected the Budget capability but omitted its
        // scope, the backward-compatible semantic default is a trip-wide budget.
        // Otherwise a specialist-scoped ceiling belongs to that specialist and
        // must not schedule the generic Budget Agent. This is semantic contract
        // validation, not lexical intent detection.
        if (plan.isNeedsBudget() && "NONE".equals(scope)) {
            plan.setBudgetScope("TRIP");
            scope = "TRIP";
        }
        if (!"TRIP".equals(scope)) {
            plan.setNeedsBudget(false);
        }
        return plan;
    }

    private String capabilitySummary(IntentPlan plan) {
        return "[flights=" + plan.isNeedsFlights()
                + ",hotels=" + plan.isNeedsHotels()
                + ",research=" + plan.isNeedsResearch()
                + ",weather=" + plan.isNeedsWeather()
                + ",budget=" + plan.isNeedsBudget()
                + ",itinerary=" + plan.isNeedsItinerary()
                + ",knowledge=" + plan.isNeedsKnowledge() + "]";
    }

    /**
     * Classifies ONLY the latest user modification.
     *
     * Important:
     * - Does not use the previous IntentPlan.
     * - Does not use previous specialist requirements.
     * - Does not use previous specialist results.
     * - Does not use validation errors.
     * - Does not use ReplanStrategy.
     *
     * The LLM determines the semantic intent of the latest request.
     * Java then owns graph routing.
     */
    public Map<String, Object> classifyLatestRequest(
            TravelState state) {

        IntentPlan plan =
                classifyLatestRequestPlan(state);

        return toUpdates(state, plan);
    }

    /**
     * Same as classifyLatestRequest(), but returns the structured
     * IntentPlan directly.
     */
    public IntentPlan classifyLatestRequestPlan(
            TravelState state) {

        if (state == null) {
            return emptyPlan();
        }

        String latestRequest = state.userRequest();

        if (TravelState.isBlank(latestRequest)) {
            return emptyPlan();
        }

        try {

            String system = """
                    You are the Intent Agent for a travel planning system.

                    Your ONLY job is to understand the user's LATEST REQUEST
                    and determine which specialist capabilities are requested
                    by that request.

                    Do NOT reason from any previous plan.

                    Do NOT preserve previous specialist requirements.

                    Do NOT use existing trip data.

                    Do NOT use validation errors.

                    Do NOT use previous specialist results.

                    Do NOT infer capabilities merely because they would be useful.

                    Return exactly ONE JSON object and NOTHING else.

                    JSON shape:

                    {
                      "requestType":"",
                      "needsFlights":false,
                      "needsHotels":false,
                      "needsResearch":false,
                      "needsWeather":false,
                      "needsBudget":false,
                      "budgetScope":"NONE",
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "needsHistory":false,
                      "strategy":"",
                      "priority":"",
                      "confidence":0.0
                    }

                    Specialist meanings:

                    needsFlights:
                    TRUE only when the user asks for flight information,
                    flight options, airfare, flights, departure/arrival
                    flight details, or flight changes.

                    needsHotels:
                    TRUE only when the user asks for hotels,
                    accommodation, rooms, hotel options,
                    hotel changes, or hotel details.

                    needsResearch:
                    TRUE only when the user asks for destinations,
                    attractions, activities, places, sightseeing,
                    recommendations, or destination research.

                    needsWeather:
                    TRUE only when the user asks about weather,
                    forecast, temperature, rain, or weather conditions.

                    needsBudget:
                    TRUE only when the user explicitly asks for overall trip budget/cost,
                    total expenses, affordability of the whole trip, cost estimation,
                    or a trip-wide cost breakdown. A price ceiling that limits another
                    specialist must NOT activate needsBudget.

                    budgetScope:
                    TRIP when the monetary objective applies to the overall trip; HOTEL when
                    it applies to accommodation; FLIGHT when it applies to flights; ACTIVITY
                    when it applies to activities; OTHER for another specific scope; NONE when
                    there is no monetary constraint. The scope is semantic, not keyword-based.

                    needsItinerary:
                    TRUE only when the user asks for a day-by-day itinerary,
                    trip schedule, or an itinerary change.

                    IMPORTANT RULES:

                    1. Every needs* field describes ONLY the LATEST USER REQUEST.

                    2. If a capability is not requested by the latest request,
                       that field MUST be false.

                    3. Evaluate the whole request, including purpose and context. A
                       live lookup can legitimately coexist with knowledge when the
                       user asks what the live information means for their travel.
                       Example: weather plus an explicit request to prepare for a visit
                       can produce needsWeather=true and needsKnowledge=true. A plain
                       weather lookup can remain needsKnowledge=false.

                    4. Never copy capability flags from the existing trip.

                    3. Do NOT copy requirements from an earlier request.

                    4. Do NOT activate a specialist because the information
                       exists in the current trip.

                    5. Do NOT activate budget just because the trip has a budget.

                    6. Do NOT activate hotels just because the trip contains
                       hotel information.

                    7. Do NOT activate flights just because the trip contains
                       flight information.

                    8. Do NOT activate itinerary just because this is a trip.

                    9. Select only capabilities actually requested by the user.

                    10. Multiple fields may be true if the latest request
                        explicitly asks for multiple capabilities.

                    Examples:

                    User:
                    "include flight details from Bangalore to Mumbai"

                    Output:
                    {
                      "requestType":"FLIGHT_DETAILS",
                      "needsFlights":true,
                      "needsHotels":false,
                      "needsResearch":false,
                      "needsWeather":false,
                      "needsBudget":false,
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "also show hotel options"

                    Output:
                    {
                      "requestType":"HOTEL_DETAILS",
                      "needsFlights":false,
                      "needsHotels":true,
                      "needsResearch":false,
                      "needsWeather":false,
                      "needsBudget":false,
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "give me weather for Mumbai"

                    Output:
                    {
                      "requestType":"WEATHER",
                      "needsFlights":false,
                      "needsHotels":false,
                      "needsResearch":false,
                      "needsWeather":true,
                      "needsBudget":false,
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "give me a budget breakdown"

                    Output:
                    {
                      "requestType":"BUDGET",
                      "needsFlights":false,
                      "needsHotels":false,
                      "needsResearch":false,
                      "needsWeather":false,
                      "needsBudget":true,
                      "budgetScope":"TRIP",
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "show me hotels and flights"

                    Output:
                    {
                      "requestType":"HOTEL_AND_FLIGHT",
                      "needsFlights":true,
                      "needsHotels":true,
                      "needsResearch":false,
                      "needsWeather":false,
                      "needsBudget":false,
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "change day 2 itinerary"

                    Output:
                    {
                      "requestType":"ITINERARY_CHANGE",
                      "needsFlights":false,
                      "needsHotels":false,
                      "needsResearch":false,
                      "needsWeather":false,
                      "needsBudget":false,
                      "needsItinerary":true,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "find attractions in Mumbai"

                    Output:
                    {
                      "requestType":"RESEARCH",
                      "needsFlights":false,
                      "needsHotels":false,
                      "needsResearch":true,
                      "needsWeather":false,
                      "needsBudget":false,
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    User:
                    "give me weather and budget details"

                    Output:
                    {
                      "requestType":"WEATHER_AND_BUDGET",
                      "needsFlights":false,
                      "needsHotels":false,
                      "needsResearch":false,
                      "needsWeather":true,
                      "needsBudget":true,
                      "budgetScope":"TRIP",
                      "needsItinerary":false,
                      "needsKnowledge":false,
                      "strategy":"none",
                      "priority":"normal",
                      "confidence":0.95
                    }

                    Do NOT add a capability merely because it would be useful.
                    """;

            /*
             * IMPORTANT FIX:
             *
             * Do NOT send the current trip context here.
             *
             * The previous implementation sent:
             * origin, destination, dates, travelers, budget,
             * travelStyle, etc.
             *
             * That allowed the small LLM to incorrectly infer
             * needsBudget=true simply because a budget existed
             * in the trip.
             *
             * For modification intent classification, the latest
             * user request is the authoritative semantic input.
             */
            String user = """
                    LATEST USER REQUEST:

                    %s

                    Classify ONLY this request.
                    """.formatted(latestRequest);

            String content =
                    routedLlm.complete(
                            AgentRole.EXTRACT,
                            system,
                            user);

            log.info(
                    "Latest intent raw LLM response={}",
                    content);

            IntentPlan parsed =
                    jsonSupport
                            .read(content, IntentPlan.class)
                            .orElseGet(this::emptyPlan);

            /*
             * Some small models omit confidence.
             * Keep the existing safe fallback.
             */
            if (parsed.getConfidence() <= 0) {
                parsed.setConfidence(0.80);
            }

            log.info(
                    "Latest intent result requestType={} flights={} hotels={} research={} weather={} budget={} itinerary={} confidence={}",
                    parsed.getRequestType(),
                    parsed.isNeedsFlights(),
                    parsed.isNeedsHotels(),
                    parsed.isNeedsResearch(),
                    parsed.isNeedsWeather(),
                    parsed.isNeedsBudget(),
                    parsed.isNeedsItinerary(),
                    parsed.getConfidence());

            return parsed;

        } catch (Exception exception) {
            if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;

            log.warn(
                    "Latest intent LLM classification failed",
                    exception);

            return emptyPlan();
        }
    }

    /**
     * Converts the IntentPlan into TravelState updates.
     *
     * TravelState.applyIntentAndRun() owns the mapping between
     * intent capabilities and NEEDS/RUN flags.
     */
    private Map<String, Object> toUpdates(TravelState state, IntentPlan plan) {
        Map<String, Object> updates = new LinkedHashMap<>();
        if (plan == null) {
            plan = emptyPlan();
        }

        // Intent is a CURRENT-TURN decision. Never OR it with checkpoint history.
        // Historical trip data remains in the state/result collections, while these
        // fields describe only what the latest semantic decision wants to execute.
        // This is the key isolation boundary that prevents an old trip from leaking
        // flights/hotels/budget into a new specialist request.
        // Single orchestration boundary: semantic intent becomes a canonical AgentPlan.
        // All legacy NEEDS_/RUN_ fields are projected from that plan for compatibility.
        TravelState.applyIntentAndRun(updates, plan);
        updates.put(TravelState.REQUEST_TYPE, plan.getRequestType());
        updates.put(TravelState.HISTORY_SELECTION, normalizeHistorySelection(plan.getHistorySelection()));
        updates.put(TravelState.PLAN_STRATEGY, plan.getStrategy());
        updates.put(TravelState.PLAN_PRIORITY, plan.getPriority());
        updates.put(TravelState.INTENT_CONFIDENCE, plan.getConfidence());

        // Clear turn-scoped generated artifacts before executing this semantic
        // request. The checkpoint intentionally retains historical domain data,
        // but transient RAG/tips/validation output must never leak into a new turn.
        updates.put(TravelState.RAG_DECISION, "skip");
        updates.put(TravelState.RAG_QUERY, "");
        updates.put(TravelState.RAG_CONTEXT, "");
        updates.put(TravelState.RAG_ANSWER, "");
        updates.put(TravelState.RAG_SOURCES, java.util.List.of());
        updates.put(TravelState.RAG_ITERATIONS, 0);
        updates.put(TravelState.RAG_SUFFICIENT, Boolean.FALSE);
        updates.put(TravelState.RAG_RETRIEVAL_METHOD, "none");
        updates.put(TravelState.RAG_CANDIDATE_COUNT, 0);
        updates.put(TravelState.RAG_RERANKED_COUNT, 0);
        updates.put(TravelState.RAG_CONTEXT_CHARS, 0);
        updates.put(TravelState.RAG_EVIDENCE_SCORE, 0.0d);
        updates.put(TravelState.RAG_GROUNDEDNESS, 0.0d);
        updates.put(TravelState.RAG_JUDGE_PASS, Boolean.TRUE);
        updates.put(TravelState.RAG_JUDGE_REASON, "not_applicable");
        updates.put(TravelState.RAG_DESTINATION, "");
        updates.put(TravelState.RAG_COUNTRY, "");
        updates.put(TravelState.RAG_TOPICS, java.util.List.of());
        updates.put(TravelState.FINAL_TIPS, "");
        updates.put(TravelState.VALIDATION_ERRORS, java.util.List.of());
        updates.put(TravelState.SEMANTIC_NOTES, java.util.List.of());

        if (state != null) {
            updates.put(TravelState.TRIP_REQUIREMENTS,
                    TripRequirementsParser.parse(state.userRequest()));
            updates.put(TravelState.LAST_DECISION,
                    new AgentDecision(
                            "intent",
                            plan.getRequestType(),
                            plan.summary(),
                            plan.getConfidence()));
        }
        return updates;
    }

    /**
     * Refines the deterministic initial classification using the LLM.
     *
     * This method is used only for the INITIAL request.
     */
        @SuppressWarnings("unused")
        private IntentPlan refineWithLlm(
            TravelState state,
            IntentPlan fallback) {

        try {

            String content =
                    routedLlm.complete(
                            AgentRole.EXTRACT,
                            """
                            You are the Intent Agent.

                            Return JSON only.

                            Determine the user's current request semantically.

                            Do not copy requirements from any previous plan.

                            Set unrelated specialist fields to false.

                            A capability must be true only when the
                            user's request actually requires that capability.

                            JSON shape:

                            {
                              "requestType":"",
                              "needsFlights":false,
                              "needsHotels":false,
                              "needsResearch":false,
                              "needsWeather":false,
                              "needsBudget":false,
                              "needsItinerary":false,
                              "needsKnowledge":false,
                              "strategy":"",
                              "priority":"",
                              "confidence":0.0
                            }

                            Specialist rules:

                            - needsFlights=true only for flight requests.
                            - needsHotels=true only for hotel requests.
                            - needsResearch=true only for research,
                              attractions, activities or recommendations.
                            - needsWeather=true only for weather requests.
                            - needsBudget=true only for explicit budget,
                              cost or expense requests.
                            - needsItinerary=true only for itinerary
                              or schedule requests.
                            - needsKnowledge=true for durable travel knowledge or practical travel guidance
                              requested alongside another capability. It may be true for a live
                              lookup when the user asks what that information means for the trip;
                              keep it false for a purely factual live lookup.
                            - Judge this from the complete latest request and its purpose. Do not copy
                              capabilities from the existing checkpoint.

                            Do not activate a specialist merely because
                            it would be useful.

                            If the user asks only for flights,
                            needsBudget MUST be false.

                            If the user asks only for hotels,
                            needsBudget MUST be false.

                            If the user asks only for weather,
                            needsBudget MUST be false.
                            """,
                            "User request: "
                                    + state.userRequest()
                                    + "\nHeuristic guess: "
                                    + fallback.summary());

            return jsonSupport
                    .read(content, IntentPlan.class)
                    .map(parsed -> {

            if (parsed.getConfidence() <= 0) {
                            parsed.setConfidence(0.8);
                        }

                        return finalizeIntent(
                                fallback,
                                parsed);
                    })
                    .orElse(fallback);

        } catch (Exception exception) {
            if (exception instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;

            log.warn(
                    "Intent LLM fallback failed; using deterministic plan",
                    exception);

            return fallback;
        }
    }

    /**
     * Only a genuine TRIP_PLANNING classification expands
     * to the complete planning pipeline.
     *
     * For every other intent, the LLM's explicit booleans
     * are authoritative.
     *
     * There is intentionally NO OR merge with the old plan.
     */
    private IntentPlan finalizeIntent(
            IntentPlan base,
            IntentPlan llm) {

        if (IntentPlan.TRIP_PLANNING.equals(
                base.getRequestType())) {

            llm.setRequestType(
                    IntentPlan.TRIP_PLANNING);

            llm.setNeedsFlights(true);
            llm.setNeedsHotels(true);
            llm.setNeedsResearch(true);
            llm.setNeedsWeather(true);
            llm.setNeedsBudget(true);
            llm.setNeedsItinerary(true);
            llm.setNeedsKnowledge(true);
        }

        return llm;
    }

    /**
     * Returns a safe empty intent.
     *
     * Missing/invalid intent must never accidentally
     * execute a specialist.
     */
    private void emitActivity(TravelState state, String message) {
        if (state == null || progressHub == null) return;
        String threadId = state.graphThreadId();
        if (threadId == null || threadId.isBlank()) return;
        progressHub.emit(threadId, "activity", Map.of(
                "phase", "intent",
                "status", "RUNNING",
                "message", message));
    }

    private IntentPlan emptyPlan() {

        IntentPlan plan =
                new IntentPlan();

        plan.setRequestType("GENERAL");

        plan.setNeedsFlights(false);
        plan.setNeedsHotels(false);
        plan.setNeedsResearch(false);
        plan.setNeedsWeather(false);
        plan.setNeedsBudget(false);
        plan.setBudgetScope("NONE");
        plan.setNeedsItinerary(false);
        plan.setNeedsKnowledge(false);

        plan.setStrategy("none");
        plan.setPriority("none");
        plan.setConfidence(0.0);

        return plan;
    }
}