package com.example.travel.support;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic slot helpers used when the planner LLM misses budget/dates/destination.
 */
public final class TripSlotHeuristics {

    private static final Pattern DAYS = Pattern.compile("(?i)\\b(\\d{1,2})\\s*-?\\s*day");
    private static final Pattern NIGHTS = Pattern.compile("(?i)\\b(\\d{1,2})\\s*-?\\s*night");
    private static final Pattern ROUTE = Pattern.compile(
            "(?i)\\bfrom\\s+(.+?)\\s+to\\s+(.+?)(?=\\s+for\\s+|\\s+on\\s+|\\s+today\\b|[.!?]|$)");
    private static final Pattern FROM_ONLY = Pattern.compile(
            "(?i)\\bfrom\\s+(.+?)(?=\\s+(?:for|on|today|tomorrow|within|under|below)\\b|[.!?]|$)");
    private static final Pattern TO_ONLY = Pattern.compile(
            "(?i)\\bto\\s+(.+?)(?=\\s+(?:for|on|today|tomorrow|within|under|below)\\b|[.!?]|$)");
    private static final Pattern BUDGET = Pattern.compile(
            "(?i)(?:under|below|within|budget(?:\\s+of)?)\\s*(₹\\s*)?([\\d,.]+\\s*(?:lakh|lac|l)\\b|[\\d,.]+)");
    private static final Pattern BUDGET_INLINE = Pattern.compile("(?i)(₹\\s*[\\d,.]+\\s*(?:lakh|lac|l)?|[\\d,.]+\\s*(?:lakh|lac|l)\\b)");

    private TripSlotHeuristics() {
    }

    /**
     * Place canonicalization is performed by QueryNormalizationService using
     * the LLM plus provider/domain validation. This helper deliberately does
     * not contain a hard-coded alias or typo dictionary.
     */
    public static String normalizePlace(String place) {
        return place == null ? "" : place.trim();
    }

    public static String extractOriginHint(String request) {
        if (request == null || request.isBlank()) return "";
        Matcher route = ROUTE.matcher(request);
        if (route.find()) return normalizePlace(route.group(1));

        // Follow-up flight questions often specify only the new origin:
        // "any flights available from Hyderabad for today".
        Matcher fromOnly = FROM_ONLY.matcher(request);
        if (fromOnly.find()) {
            return normalizePlace(cleanDestination(fromOnly.group(1)));
        }
        return findKnownPlace(request, false);
    }

    public static String extractDestinationHint(String request) {
        if (request == null || request.isBlank()) return "";
        Matcher route = ROUTE.matcher(request);
        if (route.find()) {
            return normalizePlace(cleanDestination(route.group(2)));
        }

        // Also support "flights to Dubai" / "travel to Bengaluru" when the
        // origin is inherited from conversation memory.
        Matcher toOnly = TO_ONLY.matcher(request);
        if (toOnly.find()) {
            return normalizePlace(cleanDestination(toOnly.group(1)));
        }
        return findKnownPlace(request, true);
    }

    /**
     * Route extraction must stop before trip constraints and instructions.
     * Example: "from Bangalore to dubai within the budget 2L. give me..."
     * must produce "Dubai", not "dubai within the budget 2L".
     */
    private static String cleanDestination(String value) {
        if (value == null || value.isBlank()) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceFirst("(?i)\\s+(?:within|under|below)\\s+(?:the\\s+)?budget(?:\\s+of)?\\b.*$", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+(?:within|under|below)\\s+(?:the\\s+)?\\d+\\s*(?:lakh|lac|l)\\b.*$", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+(?:with|on)\\s+(?:a\\s+)?budget\\b.*$", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+(?:for|over)\\s+\\d{1,2}\\s*-?\\s*(?:day|night)s?\\b.*$", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+(?:give|show|find|provide)\\s+me\\b.*$", "");
        cleaned = cleaned.replaceFirst("[,.!?;:]+$", "").trim();
        return cleaned;
    }

    private static String findKnownPlace(String request, boolean destination) {
        // Deliberately empty. Entity recognition and spelling correction are
        // performed by QueryNormalizationService rather than a hard-coded
        // place list.
        return "";
    }

    public static String extractBudgetLabel(String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        Matcher under = BUDGET.matcher(request);
        if (under.find()) {
            return (under.group(1) == null ? "" : under.group(1)) + under.group(2).trim();
        }
        Matcher inline = BUDGET_INLINE.matcher(request);
        if (inline.find()) {
            return inline.group(1).trim();
        }
        return "";
    }

    /**
     * Detects relative/calendar date language in the CURRENT turn. Conversation
     * memory must not overwrite these values with an older trip date.
     */
    public static boolean hasDateHint(String request) {
        if (request == null || request.isBlank()) return false;
        String text = request.toLowerCase(Locale.ROOT);
        if (text.matches(".*\\b(today|tomorrow|day after tomorrow|tonight)\\b.*")) return true;
        // Common explicit calendar-date forms: 2026-09-20, 20/09/2026, 20-09-2026.
        return text.matches(".*\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b.*")
                || text.matches(".*\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b.*");
    }

    public static boolean hasDurationHint(String request) {
        if (request == null || request.isBlank()) return false;
        return DAYS.matcher(request).find() || NIGHTS.matcher(request).find();
    }

    /**
     * Detects an explicit origin-to-destination structure without deciding
     * what the user wants to do with that route. Semantic intent remains
     * responsible for the capability decision.
     */
    public static boolean hasRouteHint(String request) {
        if (request == null || request.isBlank()) return false;
        return ROUTE.matcher(request).find();
    }

    public static LocalDate inferReturnDate(String request, LocalDate departure, LocalDate fallbackReturn) {
        LocalDate start = departure == null ? LocalDate.now() : departure;
        Matcher days = DAYS.matcher(request == null ? "" : request);
        if (days.find()) {
            int count = Integer.parseInt(days.group(1));
            return start.plusDays(Math.max(1, count - 1));
        }
        Matcher nights = NIGHTS.matcher(request == null ? "" : request);
        if (nights.find()) {
            int count = Integer.parseInt(nights.group(1));
            return start.plusDays(Math.max(1, count));
        }
        return fallbackReturn == null ? start.plusDays(5) : fallbackReturn;
    }
}
