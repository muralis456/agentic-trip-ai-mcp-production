package com.example.travel.service;

import com.example.travel.entity.AirportLocation;
import com.example.travel.repository.AirportLocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AirportLookupService {

    private final AirportLocationRepository repository;

    public AirportLookupService(AirportLocationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<AirportLocation> findAirport(String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }

        String value = destination.trim();
        if (value.matches("[A-Za-z]{3}")) {
            return repository.findByIataCodeIgnoreCase(value);
        }

        String[] parts = value.split(",");
        String city = normalizeCity(parts[0].trim().replaceFirst("(?i)\\s+city$", ""));
        Optional<AirportLocation> match = repository.findFirstByCityIgnoreCase(city);
        if (match.isPresent()) {
            return match;
        }
        if (parts.length > 1) {
            Optional<AirportLocation> byCountryPart = repository.findFirstByCountryIgnoreCase(parts[1].trim());
            if (byCountryPart.isPresent()) {
                return byCountryPart;
            }
        }

        Optional<AirportLocation> byCity = repository.findFirstByCityIgnoreCase(normalizeCity(value));
        if (byCity.isPresent()) {
            return byCity;
        }

        // Dynamic typo tolerance: resolve close city names from the canonical
        // airport directory instead of maintaining one branch per misspelling.
        Optional<AirportLocation> fuzzy = fuzzyCity(value);
        if (fuzzy.isPresent()) {
            return fuzzy;
        }

        // "Japan" / "Thailand" style prompts: resolve via country to a major hub.
        return repository.findFirstByCountryIgnoreCase(normalizeCountry(value));
    }

    private Optional<AirportLocation> fuzzyCity(String value) {
        String input = key(value);
        if (input.length() < 4) {
            return Optional.empty();
        }
        List<ScoredAirport> candidates = repository.findAll().stream()
                .filter(a -> a.getCity() != null && !a.getCity().isBlank())
                .map(a -> new ScoredAirport(a, similarity(input, key(a.getCity()))))
                .filter(s -> s.score() >= threshold(input))
                .sorted(Comparator.comparingDouble(ScoredAirport::score).reversed())
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ScoredAirport best = candidates.get(0);
        double second = candidates.size() > 1 ? candidates.get(1).score() : 0.0d;
        // Auto-resolve only when the match is strong and unambiguous. Otherwise
        // let the planner ask for clarification instead of silently choosing a
        // different city.
        if (best.score() >= 0.93d || best.score() - second >= 0.05d) {
            return Optional.of(best.airport());
        }
        return Optional.empty();
    }

    private double threshold(String input) {
        return input.length() <= 6 ? 0.84d : 0.86d;
    }

    private double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0d;
        return 1.0d - ((double) levenshtein(a, b) / max);
    }

    private int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    private String key(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private record ScoredAirport(AirportLocation airport, double score) {}

    private String normalizeCity(String city) {
        // Canonicalization is performed by QueryNormalizationService using the
        // LLM. This lookup service only resolves against the canonical airport
        // directory and fuzzy-matches unknown input.
        return city == null ? "" : city.trim();
    }

    private String normalizeCountry(String value) {
        return value == null ? "" : value.trim();
    }

}
