package com.example.travel.service;



import com.example.travel.dto.AgentExecutionDetails;
import com.example.travel.dto.KnowledgeGuidance;

import com.example.travel.dto.PlanValidationView;

import com.example.travel.dto.TripHeader;

import com.example.travel.dto.TripPlanResult;

import com.example.travel.graph.TravelState;

import com.example.travel.model.Itinerary;

import com.example.travel.model.PlanQualityScore;

import com.example.travel.model.ProvenanceEvent;

import com.example.travel.model.TripRequirements;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.time.Instant;
import java.util.ArrayList;

import java.util.LinkedHashSet;

import java.util.List;

import java.util.Locale;

import java.util.Set;



/**

 * Maps LangGraph {@link TravelState} into a domain presentation model for the UI/API.

 */

@Service

public class TripPlanAssembler {

    private static final Logger log = LoggerFactory.getLogger(TripPlanAssembler.class);

    private final RequirementEvaluator requirementEvaluator;



    public TripPlanAssembler(RequirementEvaluator requirementEvaluator) {

        this.requirementEvaluator = requirementEvaluator;

    }



    public TripPlanResult assemble(TravelState state, String status, boolean awaitingApproval) {

        TripPlanResult result = new TripPlanResult();

        result.setTrip(buildHeader(state, status, awaitingApproval));

        // A specialist response is a view of the CURRENT request, not a replay
        // of every capability accumulated in the checkpoint.  NEEDS_* is
        // intentionally cumulative for trip planning, while RUN_* describes
        // exactly what executed for this turn.  Mixing the two here was the
        // source of weather-only responses leaking old budget/hotel/flight data.
        boolean fullTripReport = isTripPlanningWorkflow(state);

        if ((fullTripReport ? state.includeFlightsInReport() : state.runFlights())
                && state.hasUsableFlights()) {

            result.setFlights(state.flights().stream()

                    .filter(f -> f != null && !"unavailable".equalsIgnoreCase(f.getStatus()))

                    .toList());

        }

        boolean includeHotels = fullTripReport ? state.includeHotelsInReport() : state.runHotels();
        log.info("Assembling plan requestType={} tripPlanning={} runHotels={} includeHotels={} stateHotelCount={}",
                state.requestType(), fullTripReport, state.runHotels(), includeHotels, state.hotels().size());
        if (includeHotels && state.hasHotelResults()) {
            result.setHotels(new ArrayList<>(state.hotels()));
        }

        if ((fullTripReport ? state.includeItineraryInReport() : state.runItinerary())
                && state.itinerary() != null) {

            Itinerary itinerary = state.itinerary();

            if (itinerary != null && !itinerary.isEmpty()) {

                result.setItinerary(itinerary);

            }

        }

        if ((fullTripReport ? state.includeBudgetInReport() : state.runBudget())
                && state.budgetSummary() != null) {

            result.setBudget(state.budgetSummary());

        }

        if ((fullTripReport ? state.includeWeatherInReport() : state.runWeather())
                && state.weather() != null) {

            result.setWeather(state.weather());

        }

        result.setValidation(buildValidation(state));
        result.setKnowledge(buildKnowledgeGuidance(state));

        return result;

    }



    public AgentExecutionDetails assembleExecution(TravelState state, String executionHistory) {

        AgentExecutionDetails details = new AgentExecutionDetails();

        details.setTimeline(new ArrayList<>(state.pipeline()));

        details.setSources(dedupeSources(state.provenance()));

        details.setExecutionHistory(executionHistory == null ? "" : executionHistory);
        details.setRagUsed(state.ragSufficient() || !state.ragContext().isBlank());
        details.setRagDecision(state.ragDecision());
        details.setRagQuery(state.ragQuery());
        details.setRagAnswer(state.ragAnswer());
        details.setRagSources(new ArrayList<>(state.ragSources()));
        details.setRagEvidenceScore(state.ragEvidenceScore());
        details.setRagCandidateCount(state.ragCandidateCount());
        details.setRagRerankedCount(state.ragRerankedCount());
        details.setRagIterations(state.ragIterations());
        details.setRagRetrievalMethod(state.ragRetrievalMethod());

        return details;

    }



    private KnowledgeGuidance buildKnowledgeGuidance(TravelState state) {
        KnowledgeGuidance guidance = new KnowledgeGuidance();
        guidance.setAvailable(state.ragSufficient() && !state.ragAnswer().isBlank());
        guidance.setAnswer(state.ragAnswer());
        guidance.setQuery(state.ragQuery());
        guidance.setDestination(state.ragDestination());
        guidance.setCountry(state.ragCountry());
        guidance.setTopics(state.ragTopics());
        guidance.setSources(state.ragSources());
        guidance.setEvidenceScore(state.ragEvidenceScore());

        String destination = TravelState.firstNonBlank(state.ragDestination(), state.destination());
        if (!destination.isBlank()) {
            guidance.setTitle("Travel Knowledge & Guidance for " + destination);
        }
        return guidance;
    }

    private boolean isTripPlanningWorkflow(TravelState state) {
        if (state == null) {
            return false;
        }
        return state.isTripPlanningWorkflow();
    }

    private TripHeader buildHeader(TravelState state, String status, boolean awaitingApproval) {

        TripHeader header = new TripHeader();

        Instant now = Instant.now();
        header.setGeneratedAt(now);
        header.setUpdatedAt(now);

        header.setTitle(tripTitle(state.destination()));

        header.setOrigin(state.origin());

        header.setDestination(state.destination());

        header.setOriginIata(state.originIata());

        header.setDestinationIata(state.destinationIata());

        header.setDepartureDate(state.departureDate().toString());

        header.setReturnDate(state.returnDate().toString());
        header.setDatesFlexible(state.datesFlexible());
        header.setRoundTrip(state.roundTrip());

        header.setNights((int) state.nights());

        header.setTravelers(state.travelers());

        header.setTravelStyle(state.travelStyle());

        header.setBudgetLabel(state.budgetLabel());

        header.setAudienceLabel(audienceLabel(state));
        header.setRequirements(requirementLabels(state));

        header.setStatus(status);

        header.setAwaitingApproval(awaitingApproval);
        if (awaitingApproval || state.awaitingApproval()) {
            header.setApprovalState("PENDING");
        } else if ("approve".equalsIgnoreCase(state.hitlDecision())) {
            header.setApprovalState("APPROVED");
        } else if ("reject".equalsIgnoreCase(state.hitlDecision())) {
            header.setApprovalState("REJECTED");
        } else {
            header.setApprovalState("NOT_REQUIRED");
        }

        PlanQualityScore quality = state.planQuality();

        if (quality != null && quality.getOverall() > 0) {

            int score = (int) Math.round(quality.getOverall() * 100);
            header.setQualityScore(score);
            header.setQualityLabel(qualityLabel(score));
            header.setQualityExplanation(qualityExplanation(state, score));

        }

        return header;

    }



    private PlanValidationView buildValidation(TravelState state) {

        PlanValidationView validation = new PlanValidationView();

        validation.setQuality(state.planQuality());

        validation.setErrors(new ArrayList<>(state.validationErrors()));

        validation.setSemanticNotes(new ArrayList<>(state.semanticNotes()));

        validation.setReviewItems(buildReviewItems(state));

        return validation;

    }



    private List<PlanValidationView.ReviewItem> buildReviewItems(TravelState state) {

        List<PlanValidationView.ReviewItem> items = new ArrayList<>();

        TripRequirements requirements = state.tripRequirements();



        if (requirements.isFamilyFriendly()) {

            double score = requirementEvaluator.familyScore(state, requirements);

            items.add(reviewItem(score >= 0.65, "Family-friendly activities",

                    "Itinerary lacks family-friendly activities"));

        }

        if (requirements.isFoodExperiences()) {

            double score = requirementEvaluator.foodScore(state, requirements);

            items.add(reviewItem(score >= 0.65, "Food experiences covered",

                    "Itinerary lacks food experiences"));

        }

        if (requirements.isLocalExperiences()) {

            double score = requirementEvaluator.localScore(state, requirements);

            items.add(reviewItem(score >= 0.65, "Local experiences included",

                    "Itinerary lacks local experiences"));

        }

        if (requirements.isBudgetConscious()) {

            items.add(new PlanValidationView.ReviewItem("ok", "Budget considered"));

        }

        if (state.budgetSummary() != null && state.budgetSummary().isWithinBudget()) {

            items.add(new PlanValidationView.ReviewItem("ok", "Within budget ceiling"));

        } else if (requirements.isBudgetConscious() && state.budgetSummary() != null) {

            items.add(new PlanValidationView.ReviewItem("warn", "Over budget ceiling"));

        }

        for (String note : state.semanticNotes()) {

            items.add(new PlanValidationView.ReviewItem("warn", note));

        }

        for (String error : state.validationErrors()) {

            items.add(new PlanValidationView.ReviewItem("warn", error));

        }

        if (items.isEmpty()) {

            items.add(new PlanValidationView.ReviewItem("ok", "Plan validated"));

        }

        return items;

    }



    private PlanValidationView.ReviewItem reviewItem(boolean passed, String okText, String warnText) {

        return passed

                ? new PlanValidationView.ReviewItem("ok", okText)

                : new PlanValidationView.ReviewItem("warn", warnText);

    }



    private List<String> dedupeSources(List<ProvenanceEvent> provenance) {

        Set<String> seen = new LinkedHashSet<>();

        List<String> lines = new ArrayList<>();

        for (ProvenanceEvent event : provenance) {

            if (event == null) {

                continue;

            }

            String line = event.toDisplay();

            if (seen.add(line)) {

                lines.add(line);

            }

        }

        return lines;

    }



    private String tripTitle(String destination) {

        String dest = TravelState.firstNonBlank(destination, "Trip");

        if (dest.toLowerCase(Locale.ROOT).contains("japan")) {

            return "Japan Trip";

        }

        return dest + " Trip";

    }



    private String audienceLabel(TravelState state) {
        // Do not infer the party type from a preference such as "family-friendly".
        // A family-friendly request can still be submitted with one traveler.
        return state.travelers() > 1 ? state.travelers() + " travelers" : "1 traveler";
    }

    private List<String> requirementLabels(TravelState state) {
        TripRequirements r = state.tripRequirements();
        List<String> labels = new ArrayList<>();
        if (r.isFamilyFriendly()) labels.add("Family-friendly");
        if (r.isFoodExperiences()) labels.add("Good food");
        if (r.isLocalExperiences()) labels.add("Local experiences");
        if (r.isBudgetConscious()) labels.add("Budget-aware");
        return labels;
    }

    private String qualityLabel(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 80) return "Good";
        if (score >= 70) return "Fair";
        return "Needs review";
    }

    private String qualityExplanation(TravelState state, int score) {
        List<String> basis = new ArrayList<>();
        if (state.needsBudget()) basis.add("budget fit");
        if (state.needsFlights()) basis.add("flight availability");
        if (state.needsHotels()) basis.add("hotel coverage");
        if (state.needsItinerary()) basis.add("itinerary completeness");
        if (state.tripRequirements().hasExplicitPreferences()) basis.add("requested preferences");
        if (state.needsWeather()) basis.add("weather coverage");
        if (basis.isEmpty()) basis.add("available plan evidence");
        return "Plan score " + score + "/100 based on " + String.join(", ", basis)
                + ". It is a planning-confidence indicator, not a guarantee of booking availability.";
    }

}


