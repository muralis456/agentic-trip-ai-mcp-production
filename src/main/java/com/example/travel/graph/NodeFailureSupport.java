package com.example.travel.graph;

import com.example.travel.model.NodeFailureInfo;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NodeFailureSupport {

    private NodeFailureSupport() {
    }

    public static Map<String, Object> record(String node, TravelState state, Exception ex, boolean retryable, int previousRetries) {
        NodeFailureInfo failure = new NodeFailureInfo();
        failure.setLastFailedNode(node);
        String errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        failure.setLastError(errorMessage);
        String normalizedError = errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (!retryable && (normalizedError.contains("rate limit")
                || normalizedError.contains("quota")
                || normalizedError.contains("http 429")
                || normalizedError.contains("provider_http_429"))) {
            failure.setFailureType("RATE_LIMITED");
        } else {
            failure.setFailureType(ex.getClass().getSimpleName());
        }
        failure.setRetryable(retryable);
        failure.setNodeRetryCount(previousRetries + 1);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(TravelState.NODE_FAILURE, failure);
        updates.putAll(TravelState.trace(node, "fail", failure.getLastError()));
        GraphExecutionLogger.nodeFailure(node, state, failure.getLastError(), retryable, failure.getNodeRetryCount());
        return updates;
    }

    public static Map<String, Object> clear() {
        return Map.of(TravelState.NODE_FAILURE, new NodeFailureInfo());
    }
}
