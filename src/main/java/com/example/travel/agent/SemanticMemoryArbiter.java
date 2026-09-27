package com.example.travel.agent;

import com.example.travel.model.MemoryIntentDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Semantic recovery signal for memory intent.
 *
 * This is deliberately meaning-based. It does not inspect keywords or regular
 * expressions. It compares the current request with semantic descriptions of
 * memory operations so a weak/ambiguous local intent LLM cannot accidentally
 * turn a request to recall an old trip into a brand-new itinerary.
 */
@Service
public class SemanticMemoryArbiter {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryArbiter.class);

    private static final String HISTORY_ONLY_MEANING = """
            The user wants to retrieve, recall, reopen, inspect, summarize or show
            information that was already saved in their previous travel plans or
            conversations. They want an existing saved trip, not a newly generated
            trip plan and not a new live travel search.
            """;

    private static final String HISTORY_CONTEXT_MEANING = """
            The user wants a new travel task, but wants the assistant to use an
            earlier saved trip, previous itinerary, prior preferences or remembered
            travel conversation as context while doing the new task.
            """;

    private static final String NEW_TRAVEL_TASK_MEANING = """
            The user wants a new travel task such as creating, planning, searching,
            comparing or changing travel information now, without asking to retrieve
            an existing saved trip or previous conversation.
            """;

    private final EmbeddingModel embeddingModel;
    private volatile List<float[]> prototypeVectors;

    public SemanticMemoryArbiter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public MemoryIntentDecision recover(String request) {
        if (request == null || request.isBlank()) {
            return new MemoryIntentDecision(false, false, 0.0);
        }

        try {
            List<float[]> prototypes = prototypes();
            float[] query = embeddingModel.embed(request);
            if (query == null || prototypes.size() != 3) {
                return new MemoryIntentDecision(false, false, 0.0);
            }

            double historyOnly = cosine(query, prototypes.get(0));
            double historyContext = cosine(query, prototypes.get(1));
            double newTask = cosine(query, prototypes.get(2));

            double bestHistory = Math.max(historyOnly, historyContext);
            double best = Math.max(bestHistory, newTask);
            double margin = bestHistory - newTask;

            // Embedding similarity is not a probability. Use a conservative
            // semantic threshold plus a margin over the competing new-task meaning.
            boolean history = bestHistory >= 0.58d && margin >= 0.06d;
            boolean only = history && historyOnly >= historyContext;
            double confidence = history
                    ? Math.min(0.94d, Math.max(0.60d, 0.50d + bestHistory * 0.35d + margin * 0.50d))
                    : Math.min(0.55d, Math.max(0.0d, best));

            log.info("Semantic memory scores request='{}' historyOnly={} historyContext={} newTask={} margin={} decision=[history={},historyOnly={},confidence={}]",
                    request, round(historyOnly), round(historyContext), round(newTask), round(margin),
                    history, only, round(confidence));

            return new MemoryIntentDecision(history, only, confidence);
        } catch (Exception ex) {
            if (ex instanceof com.example.travel.exception.GraphStopRequestedException stop) throw stop;
            log.warn("Semantic memory embedding recovery failed", ex);
            return new MemoryIntentDecision(false, false, 0.0);
        }
    }

    private List<float[]> prototypes() {
        List<float[]> existing = prototypeVectors;
        if (existing != null && existing.size() == 3) {
            return existing;
        }
        synchronized (this) {
            if (prototypeVectors == null || prototypeVectors.size() != 3) {
                prototypeVectors = embeddingModel.embed(List.of(
                        HISTORY_ONLY_MEANING,
                        HISTORY_CONTEXT_MEANING,
                        NEW_TRAVEL_TASK_MEANING));
            }
            return prototypeVectors;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0f;
        }
        double dot = 0.0d;
        double aa = 0.0d;
        double bb = 0.0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            aa += (double) a[i] * a[i];
            bb += (double) b[i] * b[i];
        }
        if (aa == 0.0d || bb == 0.0d) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(aa) * Math.sqrt(bb)));
    }
}
