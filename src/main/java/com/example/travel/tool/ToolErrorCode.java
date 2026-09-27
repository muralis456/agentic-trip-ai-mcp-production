package com.example.travel.tool;

public enum ToolErrorCode {
    NONE(false),
    RETRYABLE(true),
    RATE_LIMITED(false),
    TIMEOUT(true),
    UNAUTHORIZED(false),
    FORBIDDEN(false),
    INVALID_INPUT(false),
    NOT_FOUND(false),
    MISSING_KEY(false),
    UNKNOWN(false);

    private final boolean retryable;

    ToolErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
