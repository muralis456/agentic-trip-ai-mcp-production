package com.example.travel.support;

import com.example.travel.tool.ToolErrorCode;

public final class ToolFailureClassifier {

    private ToolFailureClassifier() {
    }

    public static ToolErrorCode fromHttp(int status, String body) {
        String text = body == null ? "" : body.toLowerCase();
        if (status == 429 || text.contains("rate limit")) {
            return ToolErrorCode.RATE_LIMITED;
        }
        if (status == 401) {
            return ToolErrorCode.UNAUTHORIZED;
        }
        if (status == 403 || text.contains("function_access_restricted")) {
            return ToolErrorCode.FORBIDDEN;
        }
        if (status == 404) {
            return ToolErrorCode.NOT_FOUND;
        }
        if (status == 400 || status == 422) {
            return ToolErrorCode.INVALID_INPUT;
        }
        if (status >= 500) {
            return ToolErrorCode.RETRYABLE;
        }
        return ToolErrorCode.UNKNOWN;
    }

    public static ToolErrorCode fromException(Throwable throwable) {
        if (throwable == null) {
            return ToolErrorCode.UNKNOWN;
        }
        String name = throwable.getClass().getSimpleName();
        String message = throwable.getMessage() == null ? "" : throwable.getMessage().toLowerCase();
        if (message.contains("provider_http_429") || message.contains("http 429")
                || message.contains("rate limit") || message.contains("too many requests")
                || message.contains("quota reached") || message.contains("quota exceeded")) {
            return ToolErrorCode.RATE_LIMITED;
        }
        if (message.contains("provider_http_401") || message.contains("http 401")
                || message.contains("provider_http_403") || message.contains("http 403")
                || message.contains("provider_http_400") || message.contains("http 400")
                || message.contains("provider_http_422") || message.contains("http 422")
                || message.contains("provider_http_404") || message.contains("http 404")) {
            return ToolErrorCode.UNKNOWN;
        }
        if (message.matches(".*(?:provider_http_|http )5\\d{2}.*")) {
            return ToolErrorCode.RETRYABLE;
        }
        if (name.contains("Timeout") || message.contains("timed out") || message.contains("timeout")) {
            return ToolErrorCode.TIMEOUT;
        }
        if (throwable instanceof org.springframework.web.client.ResourceAccessException) {
            return ToolErrorCode.TIMEOUT;
        }
        return ToolErrorCode.UNKNOWN;
    }
}
