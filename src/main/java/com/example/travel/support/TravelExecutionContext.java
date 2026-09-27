package com.example.travel.support;

import com.example.travel.graph.TravelState;

/**
 * Small compatibility helper for tests/checkpoints that may not carry an authenticated user.
 * Live controller requests always populate userId.
 */
public final class TravelExecutionContext {
    private TravelExecutionContext() {}
    public static String userId(TravelState state) {
        String userId = state == null ? "" : state.userId();
        return userId == null || userId.isBlank() ? "test-user" : userId;
    }
}
