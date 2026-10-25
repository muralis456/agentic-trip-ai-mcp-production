package com.example.travel.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsOversizedPromptAndUnsupportedPassengerCounts() {
        TravelRequest request = new TravelRequest();
        request.setPrompt("x".repeat(8_001));
        request.setAdults(10);
        request.setChildren(-1);

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("prompt")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("adults")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("children")));
    }

    @Test
    void acceptsRequestsWithinSupportedLimits() {
        TravelRequest request = new TravelRequest();
        request.setPrompt("Plan a weekend in Tokyo");
        request.setAdults(2);
        request.setChildren(1);

        assertTrue(validator.validate(request).isEmpty());
    }
}
