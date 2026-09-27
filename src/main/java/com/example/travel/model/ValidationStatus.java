package com.example.travel.model;

public enum ValidationStatus {
    PASS,
    FAIL,
    WARN;

    public static ValidationStatus fromToken(String token) {
        if (token == null || token.isBlank()) {
            return WARN;
        }
        String upper = token.trim().toUpperCase();
        if (upper.contains("FAIL")) {
            return FAIL;
        }
        if (upper.contains("PASS")) {
            return PASS;
        }
        return WARN;
    }
}
