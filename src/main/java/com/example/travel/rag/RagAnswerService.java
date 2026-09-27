package com.example.travel.rag;

import com.example.travel.config.TravelModelsProperties.AgentRole;
import com.example.travel.graph.TravelState;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.service.RoutedLlm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns retrieved knowledge into a small, durable travel-guidance answer.
 *
 * RAG is deliberately not another trip-planning agent.  The answer contract
 * excludes flights, hotels, prices, budgets and day-by-day itineraries so the
 * knowledge card cannot duplicate the execution plan.
 */
@Service
public class RagAnswerService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerService.class);
    private static final int MAX_ANSWER_CHARS = 1400;
    private static final Pattern PLAN_SECTION = Pattern.compile(
            "(?is)(^|\\n)\\s*(#{1,6}\\s*)?(flights?|hotels?|accommodation|itinerary|day[- ]by[- ]day|budget|cost|price|fare|trip plan|schedule)\\s*[:#-]?");
    private static final Pattern INTERNAL_FAILURE = Pattern.compile(
            "(?i)(llm\\s+budget\\s+exhausted|budget\\s+exhausted|quota|rate\\s*limit|too many requests|model unavailable)");

    private final RoutedLlm routedLlm;

    public RagAnswerService(RoutedLlm routedLlm) {
        this.routedLlm = routedLlm;
    }

    public String answer(TravelState state) {
        return answer(state, state.ragQuery(), state.ragContext(),
                state.ragSufficient(), state.ragEvidenceScore(), state.ragSources());
    }

    public String answer(TravelState state, String query, String context,
                         boolean sufficient, double evidenceScore, List<String> sources) {
        if (!sufficient || context == null || context.isBlank()) {
            log.info("RAG answer blocked because evidence is insufficient. query={} score={} sources={}",
                    query, evidenceScore, sources);
            return "No additional destination guidance was found in the travel knowledge base.";
        }

        String groundedContext = sanitizeForAnswer(context);
        if (groundedContext.isBlank()) {
            return "No additional destination guidance is available right now.";
        }

        String prompt = """
                You are the travel knowledge answer writer.

                Answer ONLY with durable travel guidance grounded in the supplied facts.
                This is a KNOWLEDGE CARD, not a trip planner.

                HARD OUTPUT RULES:
                - Return exactly 3 to 6 concise bullet points.
                - Each bullet should be one practical sentence.
                - Discuss durable destination guidance such as local transport, packing,
                  weather preparation, etiquette, safety, food/culture, or planning advice.
                - NEVER output flights, airlines, flight times, fares, hotels, accommodation,
                  prices, budgets, trip totals, day-by-day itineraries, booking options,
                  schedules, or a reconstructed trip plan.
                - NEVER invent facts that are absent from the grounded facts.
                - Do not mention RAG, retrieval, embeddings, documents, prompts, or sources.
                - Do not add a Sources section.
                - Do not repeat the user's complete trip request.

                USER QUESTION:
                %s

                GROUNDED FACTS:
                %s
                """.formatted(state.userRequest(), groundedContext);

        try {
            String answer = routedLlm.complete(AgentRole.FINAL, prompt,
                    "Write only the concise durable travel-guidance bullets.");
            if (isSafeKnowledgeAnswer(answer)) {
                return answer.trim();
            }
            log.warn("RAG answer rejected by knowledge-card contract; using grounded fallback");
        } catch (GraphStopRequestedException stopped) {
            throw stopped;
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new GraphStopRequestedException(ex);
            }
            log.warn("RAG answer generation failed; using grounded fallback", ex);
        }

        return groundedFallback(groundedContext, state.destination());
    }

    private boolean isSafeKnowledgeAnswer(String answer) {
        if (answer == null || answer.isBlank() || answer.length() > MAX_ANSWER_CHARS) {
            return false;
        }
        if (INTERNAL_FAILURE.matcher(answer).find() || PLAN_SECTION.matcher(answer).find()) {
            return false;
        }
        // The contract is deliberately stricter than heading detection because a
        // model can put trip-plan facts inside ordinary bullet points.
        String lower = answer.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(?:flight|airline|hotel|accommodation|fare|budget|itinerary|trip total|booking|schedule)\\b.*")) {
            return false;
        }
        if (lower.contains("total") || lower.contains("price") || lower.contains("cost")) {
            return false;
        }
        if (answer.matches("(?s).*\\b20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}\\b.*")) {
            return false;
        }
        int currencyMarks = count(answer, '₹') + count(answer, '$') + count(answer, '€');
        return currencyMarks == 0;
    }

    private String groundedFallback(String context, String destination) {
        List<String> bullets = new ArrayList<>();
        for (String raw : context.split("\\R")) {
            String line = raw.trim();
            if (line.isBlank() || line.startsWith("#") || line.startsWith("[")
                    || line.length() < 35) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (PLAN_SECTION.matcher(line).find()
                    || lower.contains("source:") || lower.contains("price")
                    || lower.contains("fare") || lower.contains("flight")
                    || lower.contains("hotel") || lower.contains("budget")
                    || lower.contains("total") || lower.contains("cost")
                    || lower.contains("airline") || lower.contains("accommodation")
                    || lower.matches(".*\\b20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}\\b.*")) {
                continue;
            }
            line = line.replaceFirst("^[-*•]\\s*", "").trim();
            if (line.length() > 260) line = line.substring(0, 257).trim() + "…";
            bullets.add(line);
            if (bullets.size() == 5) break;
        }
        if (bullets.isEmpty()) {
            String place = destination == null || destination.isBlank() ? "your destination" : destination;
            return "- Check local transport and payment practices before travelling to " + place + ".\n"
                    + "- Keep weather-appropriate clothing and a small contingency plan.\n"
                    + "- Respect local etiquette and follow local safety guidance.";
        }
        return String.join("\n", bullets);
    }

    private String sanitizeForAnswer(String context) {
        if (context == null || context.isBlank()) return "";
        return context
                .replaceAll("(?im)^\\s*\\[Source:.*?\\]\\s*$", "")
                .replaceAll("(?is)##\\s*(RAG usage|Live-data boundary|Authoritative web references)\\b.*?(?=\\n##\\s+|\\z)", "")
                .replaceAll("(?im)^\\s*Sources?:\\s*.*$", "")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
    }

    private int count(String text, char needle) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == needle) n++;
        return n;
    }
}
