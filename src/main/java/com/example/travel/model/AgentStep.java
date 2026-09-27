package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

public class AgentStep implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String node;
    private String status;
    private String detail;
    private long durationMs;
    private String model;
    private String toolCalls;
    private String error;
    private int retryCount;
    private int attempt;
    private int inputTokens;
    private int outputTokens;

    public AgentStep() {
    }

    public AgentStep(String node, String status, String detail) {
        this(node, status, detail, 0);
    }

    public AgentStep(String node, String status, String detail, long durationMs) {
        this.node = node;
        this.status = normalizeStatus(status);
        this.detail = detail;
        this.durationMs = durationMs;
    }

    public static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "SUCCESS";
        }
        return switch (status.toLowerCase()) {
            case "ok", "success" -> "SUCCESS";
            case "warn", "warning", "partial" -> "PARTIAL";
            case "skip", "skipped" -> "SKIPPED";
            case "fail", "failed", "error" -> "FAILED";
            case "retry", "retrying" -> "RETRYING";
            case "waiting", "waiting_human", "hitl" -> "WAITING_HUMAN";
            default -> status.toUpperCase();
        };
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }
}
