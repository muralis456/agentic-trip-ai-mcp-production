package com.example.travel.agent;

import com.example.travel.model.IntentPlan;
import org.springframework.ai.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Semantic capability adjudicator.
 *
 * This is deliberately NOT a keyword classifier. It compares the meaning of
 * the user's request with semantic capability descriptions using the same
 * local embedding model already used by the RAG pipeline.
 *
 * The LLM remains the primary intent reasoner. This component is a recovery
 * and consistency signal for noisy, typo-heavy, short, or ambiguous requests.
 */
@Service
public class SemanticIntentArbiter {

    private static final Logger log =
            LoggerFactory.getLogger(SemanticIntentArbiter.class);

    private static final double CAPABILITY_THRESHOLD = 0.50;

    private static final Map<String, String> CAPABILITY_MEANINGS =
            new LinkedHashMap<>();

    static {
        CAPABILITY_MEANINGS.put(
                "flights",
                "The user wants airline flights, airfare, flight options, flight availability, "
                        + "departure and arrival flight information, or help changing or finding flights.");

        CAPABILITY_MEANINGS.put(
                "hotels",
                "The user wants accommodation, hotels, rooms, lodging, neighborhoods or areas "
                        + "that are suitable for staying overnight.");

        CAPABILITY_MEANINGS.put(
                "research",
                "The user wants current destination research, recommendations, attractions, "
                        + "activities, current local information, or suggestions that benefit from web research.");

        CAPABILITY_MEANINGS.put(
                "weather",
                "The user wants current weather, a forecast, temperature, rain conditions, "
                        + "weather suitability, or weather-dependent activity advice.");

        CAPABILITY_MEANINGS.put(
                "budget",
                "The user wants travel cost estimation, price comparison, spending calculation, "
                        + "budget optimization, or a trip cost breakdown.");

        CAPABILITY_MEANINGS.put(
                "itinerary",
                "The user wants a trip organized into a schedule, a day-by-day travel plan, "
                        + "a vacation plan, or changes to an existing travel schedule.");

        CAPABILITY_MEANINGS.put(
                "knowledge",
                "The user wants general or durable travel knowledge, destination advice, "
                        + "travel tips, local practical guidance, culture, history, customs, "
                        + "safety, packing advice, visa guidance, or a destination overview.");
    }

    private final EmbeddingModel embeddingModel;
    private final AtomicReference<List<float[]>> prototypeVectors = new AtomicReference<>();

    public SemanticIntentArbiter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public IntentPlan recover(String request, IntentPlan llmPlan) {
        if (request == null || request.isBlank()) {
            return empty();
        }

        try {
            List<float[]> prototypes = prototypeVectors.updateAndGet(existing -> {
                if (existing != null && existing.size() == CAPABILITY_MEANINGS.size()) {
                    return existing;
                }
                return embeddingModel.embed(List.copyOf(CAPABILITY_MEANINGS.values()));
            });

            float[] query = embeddingModel.embed(request);
            if (query == null || prototypes.size() != CAPABILITY_MEANINGS.size()) {
                return empty();
            }

            Map<String, Double> scores = new LinkedHashMap<>();

            int i = 0;
            for (String capability : CAPABILITY_MEANINGS.keySet()) {
                scores.put(capability, (double) cosine(query, prototypes.get(i++)));
            }

            log.info("Semantic intent scores request='{}' scores={}", request, scores);

            IntentPlan result = new IntentPlan();

            double best = 0.0;
            String bestCapability = "";

            for (Map.Entry<String, Double> entry : scores.entrySet()) {
                if (entry.getValue() > best) {
                    best = entry.getValue();
                    bestCapability = entry.getKey();
                }
            }

            // Require a meaningful semantic match. This prevents arbitrary
            // short prompts from becoming travel actions.
            if (best < CAPABILITY_THRESHOLD) {
                return empty();
            }

            result.setNeedsFlights(score(scores, "flights") >= CAPABILITY_THRESHOLD);
            result.setNeedsHotels(score(scores, "hotels") >= CAPABILITY_THRESHOLD);
            result.setNeedsResearch(score(scores, "research") >= CAPABILITY_THRESHOLD);
            result.setNeedsWeather(score(scores, "weather") >= CAPABILITY_THRESHOLD);
            result.setNeedsBudget(score(scores, "budget") >= CAPABILITY_THRESHOLD);
            result.setNeedsItinerary(score(scores, "itinerary") >= CAPABILITY_THRESHOLD);
            result.setNeedsKnowledge(score(scores, "knowledge") >= CAPABILITY_THRESHOLD);

            // Resolve semantic source conflicts dynamically. The embedding model
            // is a second semantic opinion. Importantly, evaluate knowledge AFTER
            // setting it; the previous implementation checked result.isNeedsKnowledge()
            // before assigning the knowledge capability, so the veto could never
            // fire for knowledge-dominant requests.
            double knowledgeScore = score(scores, "knowledge");
            double researchScore = score(scores, "research");
            if (result.isNeedsKnowledge()
                    && result.isNeedsResearch()
                    && knowledgeScore > researchScore
                    && knowledgeScore - researchScore >= 0.07d) {
                result.setNeedsResearch(false);
                log.info("Semantic capability conflict resolved: knowledge={} research={} margin={} -> research=false",
                        knowledgeScore, researchScore, knowledgeScore - researchScore);
            }

            // The embedding classifier is deliberately conservative about
            // multi-capability activation. It is primarily a recovery signal.
            if (!result.isNeedsKnowledge()
                    && !result.isNeedsFlights()
                    && !result.isNeedsHotels()
                    && !result.isNeedsResearch()
                    && !result.isNeedsWeather()
                    && !result.isNeedsBudget()
                    && !result.isNeedsItinerary()) {
                activateBest(result, bestCapability);
            }

            String type = requestType(result);
            result.setRequestType(type);
            result.setStrategy(strategy(result));
            result.setPriority(priority(result));

            // Embedding similarity is not a calibrated probability. Convert
            // the score to a bounded confidence signal without pretending it is
            // an LLM probability.
            result.setConfidence(Math.min(0.95, Math.max(0.60, best)));

            return result;

        } catch (Exception ex) {
            if (ex instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Semantic intent embedding recovery failed", ex);
            return empty();
        }
    }

    private static void activateBest(IntentPlan result, String best) {
        switch (best) {
            case "flights" -> result.setNeedsFlights(true);
            case "hotels" -> result.setNeedsHotels(true);
            case "research" -> result.setNeedsResearch(true);
            case "weather" -> result.setNeedsWeather(true);
            case "budget" -> result.setNeedsBudget(true);
            case "itinerary" -> result.setNeedsItinerary(true);
            case "knowledge" -> result.setNeedsKnowledge(true);
            default -> {
            }
        }
    }

    private static String requestType(IntentPlan p) {
        if (p.isNeedsItinerary()) return "TRIP_PLANNING";
        if (p.isNeedsFlights() && !p.isNeedsHotels() && !p.isNeedsWeather()
                && !p.isNeedsResearch() && !p.isNeedsKnowledge()) return "FLIGHT_SEARCH";
        if (p.isNeedsHotels() && !p.isNeedsFlights() && !p.isNeedsWeather()
                && !p.isNeedsResearch() && !p.isNeedsKnowledge()) return "HOTEL_SEARCH";
        if (p.isNeedsWeather() && !p.isNeedsFlights() && !p.isNeedsHotels()
                && !p.isNeedsResearch() && !p.isNeedsKnowledge()) return "WEATHER";
        if (p.isNeedsBudget() && !p.isNeedsItinerary() && !p.isNeedsFlights()
                && !p.isNeedsHotels()) return "BUDGET";
        if (p.isNeedsKnowledge() && !p.isNeedsFlights() && !p.isNeedsHotels()
                && !p.isNeedsResearch() && !p.isNeedsWeather()
                && !p.isNeedsBudget() && !p.isNeedsItinerary()) return "TRAVEL_INFORMATION";
        if (p.isNeedsResearch() && !p.isNeedsFlights() && !p.isNeedsHotels()
                && !p.isNeedsWeather() && !p.isNeedsBudget()
                && !p.isNeedsItinerary()) return "RESEARCH";
        return "MULTI_INTENT";
    }

    private static String strategy(IntentPlan p) {
        if (p.isNeedsKnowledge() && !p.isNeedsFlights() && !p.isNeedsHotels()
                && !p.isNeedsResearch() && !p.isNeedsWeather()
                && !p.isNeedsBudget() && !p.isNeedsItinerary()) return "rag_only";
        if (p.isNeedsItinerary()) return "trip_planning";
        if (p.isNeedsFlights() && !p.isNeedsHotels() && !p.isNeedsResearch()
                && !p.isNeedsWeather()) return "flight_only";
        if (p.isNeedsHotels() && !p.isNeedsFlights() && !p.isNeedsResearch()
                && !p.isNeedsWeather()) return "hotel_only";
        return "multi_capability";
    }

    private static String priority(IntentPlan p) {
        if (p.isNeedsFlights()) return "flights";
        if (p.isNeedsHotels()) return "hotels";
        if (p.isNeedsWeather()) return "weather";
        if (p.isNeedsItinerary()) return "itinerary";
        if (p.isNeedsResearch()) return "research";
        if (p.isNeedsKnowledge()) return "knowledge";
        if (p.isNeedsBudget()) return "budget";
        return "none";
    }

    private static double score(Map<String, Double> scores, String key) {
        return scores.getOrDefault(key, 0.0);
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0f;
        }
        double dot = 0;
        double aa = 0;
        double bb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            aa += (double) a[i] * a[i];
            bb += (double) b[i] * b[i];
        }
        if (aa == 0 || bb == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(aa) * Math.sqrt(bb)));
    }

    private static IntentPlan empty() {
        IntentPlan plan = new IntentPlan();
        plan.setRequestType("GENERAL");
        plan.setStrategy("none");
        plan.setPriority("none");
        plan.setConfidence(0.0);
        return plan;
    }
}
