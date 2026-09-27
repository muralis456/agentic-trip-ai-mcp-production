package com.example.travel.graph;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.model.AgentTask;
import com.example.travel.model.GoalEvaluation;
import com.example.travel.service.RoutedLlm;
import com.example.travel.support.JsonSupport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class GoalEvaluationService {
    private final RoutedLlm llm;
    private final JsonSupport json;

    public GoalEvaluationService(RoutedLlm llm, JsonSupport json) {
        this.llm = llm;
        this.json = json;
    }

    public GoalEvaluation evaluate(TravelState state) {
        GoalEvaluation hard = hardEvaluation(state);
        if (hard.getStatus() == GoalEvaluation.Status.NEEDS_USER) {
            // Clarification is a deterministic lifecycle boundary. The LLM cannot
            // reinterpret missing required input as a completed goal.
            return hard;
        }

        // Java owns the execution contract. Once the deterministic evaluator has
        // proved that every required outcome is present, the goal is achieved.
        // The LLM is allowed to add semantic reasoning for a partial result, but
        // it must never downgrade a mechanically complete execution. This prevents
        // stale checkpoint/task text or an LLM hallucination from resurrecting a
        // failed state after a successful recovery.
        if (hard.getStatus() == GoalEvaluation.Status.ACHIEVED) {
            return hard;
        }

        try {
            String system = "You are the Goal Evaluator for a production travel agent. "
                    + "Determine whether the user's actual goal is achieved from the evidence provided. "
                    + "Do not reward internal task execution. Check observable outcomes and explicit constraints. "
                    + "You MUST NOT return ACHIEVED if any required task/outcome is objectively missing or failed. "
                    + "Return JSON only: status(ACHIEVED|PARTIAL|FAILED|NEEDS_USER), score(0..1), reason, satisfiedCriteria[], unmetCriteria[], blockingIssues[], recoverable(boolean).";
            String user = "GOAL: " + state.agentPlan().getGoal()
                    + "\nSUCCESS CRITERIA: " + state.agentPlan().getSuccessCriteria()
                    + "\nTASKS: " + state.agentPlan().getTasks()
                    + "\nDESTINATION: " + state.destination()
                    + "\nBUDGET: " + state.budgetLabel()
                    + "\nBUDGET RESULT: " + state.budgetSummary()
                    + "\nFLIGHTS: " + state.flights().size()
                    + "\nHOTELS: " + state.hotels().size()
                    + "\nRESEARCH: " + state.research().size()
                    + "\nWEATHER: " + state.weather()
                    + "\nITINERARY: " + state.itinerary();
            GoalEvaluation llmEvaluation = json.read(this.llm.complete(AgentRole.PLANNER, system, user), GoalEvaluation.class).orElse(null);
            if (llmEvaluation == null) return hard;

            // Never let the model erase deterministic blockers. Merge them into
            // the semantic result while retaining useful LLM reasoning.
            llmEvaluation.setUnmetCriteria(union(hard.getUnmetCriteria(), llmEvaluation.getUnmetCriteria()));
            llmEvaluation.setSatisfiedCriteria(union(hard.getSatisfiedCriteria(), llmEvaluation.getSatisfiedCriteria()));
            llmEvaluation.setBlockingIssues(union(hard.getBlockingIssues(), llmEvaluation.getBlockingIssues()));
            if (!hard.getUnmetCriteria().isEmpty()) {
                // A hard missing outcome always dominates an LLM "achieved" claim.
                llmEvaluation.setStatus(hard.getStatus());
                boolean terminalProviderFailure = state.nodeFailure() != null
                        && !TravelState.isBlank(state.nodeFailure().getLastFailedNode())
                        && !state.nodeFailure().isRetryable();
                llmEvaluation.setRecoverable(terminalProviderFailure
                        ? false
                        : hard.isRecoverable() || llmEvaluation.isRecoverable());
            } else if (hard.getStatus() == GoalEvaluation.Status.ACHIEVED
                    && llmEvaluation.getStatus() == GoalEvaluation.Status.NEEDS_USER
                    && llmEvaluation.getUnmetCriteria().isEmpty()
                    && llmEvaluation.getBlockingIssues().isEmpty()) {
                // The LLM must not downgrade a mechanically complete trip to
                // NEEDS_USER without identifying a concrete unmet criterion or
                // blocking issue. Otherwise a complete trip can incorrectly
                // bypass the approval boundary and be auto-confirmed.
                llmEvaluation.setStatus(GoalEvaluation.Status.ACHIEVED);
                llmEvaluation.setScore(Math.max(llmEvaluation.getScore(), hard.getScore()));
                llmEvaluation.setRecoverable(false);
                llmEvaluation.setReason(hard.getReason());
            }
            return llmEvaluation;
        } catch (Exception ignored) {
            return hard;
        }
    }

    private List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out.stream()
                .filter(v -> v != null && !v.isBlank())
                // Never expose Java object identity strings such as
                // AgentTask@5d3f... to the UI. They are implementation details,
                // not user-facing recovery information.
                .filter(v -> !v.startsWith("AgentTask@"))
                .distinct()
                .toList();
    }

    private GoalEvaluation hardEvaluation(TravelState state) {
        GoalEvaluation e = new GoalEvaluation();
        List<String> satisfied = new ArrayList<>();
        List<String> unmet = new ArrayList<>();
        if (state.userInputRequired()) {
            e.setStatus(GoalEvaluation.Status.NEEDS_USER);
            e.setScore(0.0);
            e.setReason(state.userInputQuestion());
            e.setBlockingIssues(List.of(state.userInputQuestion()));
            e.setRecoverable(false);
            return e;
        }
        // Only an explicit complete-trip goal gets the complete-trip contract.
        // An itinerary-only request must not suddenly require flights, hotels,
        // weather and a budget.
        boolean trip = "TRIP_PLANNING".equalsIgnoreCase(state.agentPlan().getGoal());

        if (!state.agentPlan().getSuccessCriteria().isEmpty()) {
            // Criteria are evaluated against task outcomes first. Semantic LLM evaluation
            // below is only used when a criterion cannot be inferred mechanically.
            for (AgentTask t : state.agentPlan().getTasks()) {
                if (!t.isRequired()) continue;
                boolean evidence = switch (t.getId()) {
                    case "flights" -> state.hasUsableFlights();
                    case "hotels" -> state.hasHotelResults();
                    case "research" -> !state.research().isEmpty() || !state.attractions().isEmpty();
                    case "weather" -> state.weather() != null && (!TravelState.isBlank(state.weather().getSummary())
                            || (state.weather().getDays() != null && !state.weather().getDays().isEmpty()));
                    case "budget" -> state.hasBudgetEstimate();
                    case "itinerary" -> state.itinerary() != null && !state.itinerary().isEmpty();
                    case "knowledge" -> !TravelState.isBlank(state.ragContext()) || !TravelState.isBlank(state.ragAnswer());
                    case "history" -> t.getStatus() == AgentTask.Status.SUCCEEDED;
                    default -> false;
                };
                if (t.getStatus() == AgentTask.Status.SUCCEEDED && evidence) satisfied.add(t.getId());
                else unmet.add(t.getId());
            }
        }
        if (!trip && state.needsBudget() && state.hasBudgetEstimate()) {
            if (state.overBudget()) {
                unmet.add("within budget");
                e.setBlockingIssues(List.of("The estimated trip cost exceeds the stated budget."));
                e.setRecoverable(true);
            } else {
                satisfied.add("within budget");
            }
        }
        if (trip) {
            if (state.hasUsableFlights()) satisfied.add("usable flights"); else unmet.add("usable flights");
            if (state.hasHotelResults()) satisfied.add("hotel options"); else unmet.add("hotel options");
            if (state.hasBudgetEstimate()) satisfied.add("budget assessment"); else unmet.add("budget assessment");
            if (state.weather() != null && (!TravelState.isBlank(state.weather().getSummary()) || (state.weather().getDays() != null && !state.weather().getDays().isEmpty()))) satisfied.add("weather guidance"); else unmet.add("weather guidance");
            if (state.itinerary() != null && !state.itinerary().isEmpty()) satisfied.add("complete itinerary"); else unmet.add("complete itinerary");
            if (state.hasBudgetEstimate() && state.budget() != null && state.budget().compareTo(TravelState.UNSET_BUDGET) != 0 && state.overBudget()) {
                unmet.add("within budget");
                e.setBlockingIssues(List.of("Trip exceeds the user's stated budget"));
                e.setRecoverable(true);
            } else if (state.hasBudgetEstimate() && state.budget() != null && state.budget().compareTo(TravelState.UNSET_BUDGET) != 0) {
                satisfied.add("within budget");
            }
        }
        e.setSatisfiedCriteria(dedupe(satisfied));
        e.setUnmetCriteria(dedupe(unmet));
        if (unmet.isEmpty()) {
            e.setStatus(GoalEvaluation.Status.ACHIEVED); e.setScore(1); e.setReason("All required outcomes are present."); e.setRecoverable(false);
        } else {
            e.setStatus(GoalEvaluation.Status.PARTIAL); e.setScore((double)satisfied.size()/Math.max(1,satisfied.size()+unmet.size()));
            e.setReason("One or more required outcomes are missing.");
            // A provider-level terminal failure (for example HTTP 429/quota or an
            // open circuit) is not a planning defect. Retrying the same capability
            // without a provider/input change only creates a replan loop.
            boolean terminalProviderFailure = state.nodeFailure() != null
                    && !TravelState.isBlank(state.nodeFailure().getLastFailedNode())
                    && !state.nodeFailure().isRetryable();
            e.setRecoverable(!terminalProviderFailure);
            if (terminalProviderFailure) {
                String failure = state.nodeFailure().getLastError();
                e.setReason("Partial result: " + (failure == null || failure.isBlank()
                        ? "a required provider is temporarily unavailable" : failure));
                if (e.getBlockingIssues() == null || e.getBlockingIssues().isEmpty()) {
                    e.setBlockingIssues(List.of(failure == null || failure.isBlank()
                            ? "A required provider is temporarily unavailable." : failure));
                }
            }
        }
        return e;
    }

    private List<String> dedupe(List<String> in) { return in.stream().filter(s -> s != null && !s.isBlank()).distinct().toList(); }
}
