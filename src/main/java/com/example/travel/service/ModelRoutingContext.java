package com.example.travel.service;

/**
 * User-selected model policy for this graph run: FAST | BALANCED | REASONING,
 * or a concrete Ollama model id (contains ':').
 */
public final class ModelRoutingContext {

    private static final InheritableThreadLocal<String> POLICY = new InheritableThreadLocal<>();
    private static final InheritableThreadLocal<Complexity> COMPLEXITY = new InheritableThreadLocal<>();

    private ModelRoutingContext() {
    }

    public static void set(String policy) {
        POLICY.set(normalize(policy));
    }

    public static String get() {
        String value = POLICY.get();
        return value == null || value.isBlank() ? "BALANCED" : value;
    }

    public static void setComplexity(Complexity complexity) {
        COMPLEXITY.set(complexity == null ? Complexity.SIMPLE : complexity);
    }

    public static Complexity getComplexity() {
        Complexity value = COMPLEXITY.get();
        return value == null ? Complexity.SIMPLE : value;
    }

    public static void clear() {
        POLICY.remove();
        COMPLEXITY.remove();
    }

    public enum Complexity {
        SIMPLE,
        NORMAL,
        COMPLEX
    }

    public static String normalize(String selected) {
        if (selected == null || selected.isBlank()) {
            return "BALANCED";
        }
        String trimmed = selected.trim();
        String upper = trimmed.toUpperCase();
        if (upper.equals("FAST") || upper.equals("BALANCED") || upper.equals("REASONING")) {
            return upper;
        }
        return trimmed;
    }
}
