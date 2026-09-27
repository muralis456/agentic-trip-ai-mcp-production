package com.example.travel.tool;

import java.io.Serial;
import java.io.Serializable;

public class ToolResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final T data;
    private final ToolErrorCode errorCode;
    private final String message;
    private final boolean retryable;

    public ToolResult(boolean success, T data, ToolErrorCode errorCode, String message, boolean retryable) {
        this.success = success;
        this.data = data;
        this.errorCode = errorCode == null ? ToolErrorCode.NONE : errorCode;
        this.message = message == null ? "" : message;
        this.retryable = retryable;
    }

    public static <T> ToolResult<T> ok(T data) {
        return new ToolResult<>(true, data, ToolErrorCode.NONE, "", false);
    }

    public static <T> ToolResult<T> fail(ToolErrorCode code, String message) {
        ToolErrorCode resolved = code == null ? ToolErrorCode.UNKNOWN : code;
        return new ToolResult<>(false, null, resolved, message, resolved.isRetryable());
    }

    public boolean success() {
        return success;
    }

    public T data() {
        return data;
    }

    public ToolErrorCode errorCode() {
        return errorCode;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }
}
