package com.example.travel.exception;

/**
 * Signals that an MCP tool is side-effecting and must be explicitly approved
 * by the human before execution.
 */
public class ToolApprovalRequiredException extends RuntimeException {
    private final String toolName;

    public ToolApprovalRequiredException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
