package com.example.travel.service;

import com.example.travel.entity.AirportLocation;
import com.example.travel.repository.AirportLocationRepository;
import com.example.travel.dto.TravelRequest;
import com.example.travel.support.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes noisy travel requests before they enter the graph.
 *
 * This capability is intentionally domain/entity focused rather than a list of
 * one-off typo fixes. It resolves misspelled cities/airports/countries by
 * comparing the user's place token with the canonical airport directory.
 * Generic wording errors remain untouched; the semantic intent agent already
 * handles natural language variation. Keeping the raw request intact means the
 * user-visible conversation is never rewritten.
 */
@Service
public class QueryNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizationService.class);

    private final AirportLocationRepository airportRepository;
    private final RoutedLlm routedLlm;
    private final JsonSupport jsonSupport;

    public QueryNormalizationService(AirportLocationRepository airportRepository,
                                     RoutedLlm routedLlm,
                                     JsonSupport jsonSupport) {
        this.airportRepository = airportRepository;
        this.routedLlm = routedLlm;
        this.jsonSupport = jsonSupport;
    }

    /**
     * Normalizes only the parts that can affect routing/entity resolution.
     * The original prompt is never overwritten here; callers can use the
     * returned normalized prompt for graph processing and retain the raw text
     * for conversation history/UI.
     */
    public NormalizationResult normalize(TravelRequest request) {
        if (request == null) {
            return NormalizationResult.empty("");
        }

        String raw = firstNonBlank(request.getPrompt(), request.getPreferences(), "");
        if (raw.isBlank()) {
            return NormalizationResult.empty(raw);
        }

        NormalizationResult llmResult = normalizeWithLlm(request, raw);
        if (llmResult != null) {
            return llmResult;
        }

        // Safe fallback: preserve the user's wording when the normalization
        // model is unavailable. Entity resolution can still be handled later
        // by the domain-specific providers. There are intentionally no
        // hard-coded typo/alias dictionaries here.
        return new NormalizationResult(raw, raw, List.of(), List.of());
    }

    private NormalizationResult normalizeWithLlm(TravelRequest request, String raw) {
        String system = "You are a travel query normalization and entity-resolution component."
                + " Correct spelling, obvious grammar mistakes, spacing, abbreviations, and malformed travel terms in the user's request."
                + " Preserve the user's intent, route, dates, quantities, constraints, and meaning."
                + " Do not invent destinations, airports, hotels, dates, prices, or other facts."
                + " If a word is ambiguous, leave it unchanged rather than guessing."
                + " Resolve misspelled travel entities only when the intended entity is clear from the text."
                + " Return JSON only with fields: normalizedPrompt, corrections, entities."
                + " corrections is an array of objects with original, corrected, type, confidence."
                + " entities is an array of objects with value, code, country, type, confidence."
                + " Entity type should be CITY, AIRPORT, COUNTRY, POI, HOTEL, or OTHER."
                + " Confidence must be between 0 and 1.";

        String user = "Original user request:\n" + raw
                + "\n\nKnown conversation/request context (use only to disambiguate, never invent):"
                + "\norigin=" + firstNonBlank(request.getDepartureCity(), "")
                + "\ndestination=" + firstNonBlank(request.getDestination(), "")
                + "\ndepartureDate=" + firstNonBlank(request.getDepartureDate(), "")
                + "\nreturnDate=" + firstNonBlank(request.getReturnDate(), "")
                + "\n\nNormalize only what is necessary. Keep the output concise and machine-readable.";

        try {
            String content = routedLlm.complete(
                    com.example.travel.config.TravelModelsProperties.AgentRole.EXTRACT,
                    system,
                    user);

            return jsonSupport.read(content, LlmNormalization.class)
                    .map(result -> toNormalizationResult(raw, result))
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Query normalization model failed; preserving raw request: {}", ex.getMessage());
            return null;
        }
    }

    private NormalizationResult toNormalizationResult(String raw, LlmNormalization result) {
        String normalized = firstNonBlank(result.normalizedPrompt(), raw);
        List<Correction> corrections = result.corrections() == null
                ? List.of()
                : result.corrections().stream()
                    .filter(c -> c != null && !isBlank(c.original()) && !isBlank(c.corrected()))
                    .map(c -> new Correction(c.original().trim(), c.corrected().trim(),
                            firstNonBlank(c.type(), "OTHER").trim().toUpperCase(Locale.ROOT),
                            clampConfidence(c.confidence())))
                    .toList();

        // Some local models correctly return the correction list but leave
        // normalizedPrompt identical to the raw text. In that case, apply the
        // model-provided corrections mechanically; there is still no hard-coded
        // typo/alias dictionary in the application.
        if (normalized.equals(raw) && !corrections.isEmpty()) {
            normalized = applyModelCorrections(raw, corrections);
        }

        LinkedHashMap<String, ResolvedEntity> entities = new LinkedHashMap<>();
        if (result.entities() != null) {
            for (LlmEntity entity : result.entities()) {
                if (entity == null || isBlank(entity.value())) {
                    continue;
                }
                String value = entity.value().trim();
                String type = firstNonBlank(entity.type(), "OTHER").trim().toUpperCase(Locale.ROOT);
                String code = firstNonBlank(entity.code(), "").trim().toUpperCase(Locale.ROOT);
                String country = firstNonBlank(entity.country(), "").trim();
                double confidence = clampConfidence(entity.confidence());

                // Ground city/airport entities against our canonical airport
                // directory when possible. The LLM supplies the interpretation;
                // the database supplies canonical identifiers.
                if ("CITY".equals(type) || "AIRPORT".equals(type)) {
                    ResolvedEntity grounded = groundAirportEntity(value, code, country, type, confidence);
                    if (grounded != null) {
                        entities.putIfAbsent(type + " : " + grounded.value().toLowerCase(Locale.ROOT), grounded);
                        continue;
                    }
                }

                entities.putIfAbsent(type + " : " + value.toLowerCase(Locale.ROOT),
                        new ResolvedEntity(value, code, country, type, confidence));
            }
        }

        return new NormalizationResult(raw, normalized, corrections, List.copyOf(entities.values()));
    }

    private String applyModelCorrections(String raw, List<Correction> corrections) {
        String result = raw;
        for (Correction correction : corrections.stream()
                .sorted((a, b) -> Integer.compare(b.original().length(), a.original().length()))
                .toList()) {
            if (correction.original().equalsIgnoreCase(correction.corrected())) continue;
            result = result.replaceAll(
                    "(?i)" + java.util.regex.Pattern.quote(correction.original()),
                    java.util.regex.Matcher.quoteReplacement(correction.corrected()));
        }
        return result;
    }

    private ResolvedEntity groundAirportEntity(String value, String code, String country,
                                                String type, double confidence) {
        List<AirportLocation> airports = airportRepository.findAll();
        for (AirportLocation airport : airports) {
            boolean codeMatch = !code.isBlank() && airport.getIataCode() != null
                    && code.equalsIgnoreCase(airport.getIataCode());
            boolean cityMatch = airport.getCity() != null && value.equalsIgnoreCase(airport.getCity());
            if (codeMatch || cityMatch) {
                return new ResolvedEntity(
                        firstNonBlank(airport.getCity(), value),
                        firstNonBlank(airport.getIataCode(), code),
                        firstNonBlank(airport.getCountry(), country),
                        "AIRPORT".equals(type) ? "AIRPORT" : "CITY",
                        Math.max(confidence, codeMatch ? 0.99d : 0.95d));
            }
        }
        return null;
    }

    /** Apply the normalized request without changing the raw conversation text. */
    public void applyToRequest(TravelRequest request) {
        if (request == null) return;
        if (request.getOriginalPrompt() == null || request.getOriginalPrompt().isBlank()) {
            request.setOriginalPrompt(request.getPrompt());
        }
        NormalizationResult result = normalize(request);
        if (!result.normalizedPrompt().isBlank()) {
            request.setPrompt(result.normalizedPrompt());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    public record NormalizationResult(
            String originalPrompt,
            String normalizedPrompt,
            List<Correction> corrections,
            List<ResolvedEntity> entities) {

        public static NormalizationResult empty(String prompt) {
            return new NormalizationResult(prompt == null ? "" : prompt, prompt == null ? "" : prompt,
                    List.of(), List.of());
        }
    }

    public record Correction(String original, String corrected, String type, double confidence) {}

    public record ResolvedEntity(String value, String code, String country, String type, double confidence) {}

    record LlmNormalization(String normalizedPrompt, List<LlmCorrection> corrections, List<LlmEntity> entities) {}
    record LlmCorrection(String original, String corrected, String type, double confidence) {}
    record LlmEntity(String value, String code, String country, String type, double confidence) {}

    private static double clampConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0d;
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
