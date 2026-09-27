package com.example.travel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.security.PromptInjectionGuard;
import com.example.travel.tool.ToolGovernanceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic MCP client adapter.
 *
 * MCP tool names are intentionally not part of the domain-specific clients.
 * The MCP server advertises tools through ToolCallbackProvider; this class
 * asks an LLM to select a tool from the discovered MCP catalog and then invokes it.
 */
@Service
@ConditionalOnProperty(prefix = "travel.mcp.client", name = "enabled", havingValue = "true")
public class McpToolClient {

    private static final Logger log = LoggerFactory.getLogger(McpToolClient.class);

    private final ToolCallbackProvider toolCallbackProvider;
    private final ObjectMapper objectMapper;
    private final McpToolSelector toolSelector;
    private final java.util.Set<String> allowedTools;
    private final int maxAttempts;
    private final long timeoutMs;
    private final PromptInjectionGuard promptInjectionGuard;
    private final ToolGovernanceService toolGovernance;

    public McpToolClient(ToolCallbackProvider toolCallbackProvider,
                         ObjectMapper objectMapper,
                         McpToolSelector toolSelector,
                         @Value("${TRAVEL_MCP_CLIENT_ALLOWED_TOOLS:}") String allowedTools,
                         @Value("${travel.mcp.client.max-attempts:2}") int maxAttempts,
                         @Value("${travel.mcp.client.timeout-ms:15000}") long timeoutMs,
                         PromptInjectionGuard promptInjectionGuard,
                         ToolGovernanceService toolGovernance) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.objectMapper = objectMapper;
        this.toolSelector = toolSelector;
        this.allowedTools = allowedTools == null || allowedTools.isBlank()
            ? Set.of()
            : Arrays.stream(allowedTools.split(","))
                .map(String::trim)
                .filter(tool -> !tool.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.promptInjectionGuard = promptInjectionGuard;
        this.toolGovernance = toolGovernance;
    }

    /**
     * Invoke a tool by its exact MCP name. Kept for infrastructure/debugging
     * and backward compatibility, but domain clients should prefer callByUserInput().
     */
    public JsonNode call(String toolName, Map<String, Object> arguments) throws Exception {
        if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            throw new IllegalStateException("MCP tool is not allowed: " + toolName);
        }
        return invoke(toolName, findExact(toolName), arguments);
    }

    /**
     * Discover the MCP tools currently exposed by the connected server.
     * Spring AI populates these callbacks from the MCP tools/list response.
     */
    public List<ToolCallback> availableTools() {
        try {
            return Arrays.stream(toolCallbackProvider.getToolCallbacks()).toList();
        } catch (Exception exception) {
            log.error("mcp.client.error phase=tool-catalog errorType={} errorMessage={}",
                    exception.getClass().getName(), safeExceptionMessage(exception), exception);
            throw new IllegalStateException("Unable to read MCP tool catalog: " + safeExceptionMessage(exception), exception);
        }
    }

    /**
     * Ask the LLM to select the best discovered MCP tool for the actual user
     * task. The LLM receives only tool metadata (name/description); invocation
     * happens afterwards through the selected ToolCallback.
     */
    public JsonNode callByUserInput(String agentPurpose, String userInput,
                                    Map<String, Object> arguments) throws Exception {
        String enrichedTask = buildSelectionContext(agentPurpose, userInput, arguments);
        try {
            ToolCallback callback = findByUserInput(agentPurpose, enrichedTask);
            String toolName = callback.getToolDefinition().name();
            return invoke(toolName, callback, arguments);
        } catch (Exception exception) {
            if (isCancellation(exception)) {
                throw stopException(exception);
            }
            log.error("mcp.client.error phase=selection agentPurpose='{}' userInput='{}' errorType={} errorMessage={}",
                    abbreviate(agentPurpose), abbreviate(userInput),
                    exception.getClass().getName(), safeExceptionMessage(exception), exception);
            throw exception;
        }
    }

    private String buildSelectionContext(String agentPurpose, String userInput, Map<String, Object> arguments) {
        return "Agent purpose: " + safe(agentPurpose)
                + "\nUser task: " + safe(userInput)
                + "\nStructured arguments: " + String.valueOf(arguments == null ? Map.of() : arguments);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").trim();
    }

    public ToolCallback findByUserInput(String agentPurpose, String userInput) throws Exception {
        List<ToolCallback> candidates = availableTools().stream()
                .filter(this::isAllowed)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No MCP tools are available from the configured server.");
        }
        log.info("mcp.client.tool-catalog purpose='{}' candidates={}", agentPurpose,
                candidates.stream().map(c -> c.getToolDefinition().name()).toList());
        return toolSelector.select(agentPurpose, userInput, candidates);
    }

    /** Prefer an exact domain tool when one is exposed; otherwise use the dynamic selector. */
    public JsonNode invokePreferred(String preferredToolName, String agentPurpose,
                                    String userInput, Map<String, Object> arguments) throws Exception {
        List<ToolCallback> candidates = availableTools().stream()
                .filter(this::isAllowed)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No MCP tools are available from the configured server.");
        }
        ToolCallback selected = candidates.stream()
                .filter(callback -> preferredToolName != null
                        && preferredToolName.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            selected = toolSelector.select(agentPurpose, userInput, candidates);
        }
        if (selected == null) {
            throw new IllegalStateException("Unable to select MCP tool for " + agentPurpose);
        }
        log.info("mcp.client.domain-selection purpose='{}' selectedTool='{}' preferred='{}'",
                agentPurpose, selected.getToolDefinition().name(), preferredToolName);
        return invoke(selected.getToolDefinition().name(), selected, arguments);
    }


    /**
     * Backward-compatible capability API. The capability is now treated as
     * task context and the LLM selects the actual MCP tool from discovered
     * tools instead of this class matching names/descriptions heuristically.
     */
    public JsonNode callByCapability(String capability, Map<String, Object> arguments) throws Exception {
        return callByUserInput("MCP capability: " + capability,
                capability + "\nArguments: " + arguments, arguments);
    }

    private JsonNode invoke(String toolName, ToolCallback callback, Map<String, Object> arguments) throws Exception {
        ToolInvocationContext.Context context = ToolInvocationContext.current()
                .orElseThrow(() -> new SecurityException("Authenticated MCP invocation context is required"));
        String purpose = "MCP invocation";
        String argumentJson = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        toolGovernance.authorize(toolName, purpose, argumentJson);
        log.info("mcp.client.policy tool={} userId={} role={} approvalGranted={} decision=ALLOW",
                toolName, context.userId(), context.role(), context.approvalGranted());
        Exception last = null;
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("mcp.client.invocation-start tool={} attempt={}",
                        callback.getToolDefinition().name(), attempt);
                String response = callWithTimeout(callback, argumentJson);
                JsonNode result = responseTree(response);
                if (result.has("success")) {
                    boolean success = result.path("success").asBoolean();
                    String errorCode = result.path("errorCode").asString("");
                    String message = result.path("message").asString("");
                    if (message.isBlank()) {
                        message = result.path("summary").asString("");
                    }
                    if (success) {
                        toolGovernance.recordSuccess(toolName);
                        log.info("mcp.client.response tool={} success=true errorCode={} message={}",
                                toolName, errorCode, message);
                    } else {
                        boolean responseRetryable = isRetryableProviderResponse(errorCode, message);
                        log.error("mcp.client.error phase=server-response tool={} attempt={} success=false retryable={} errorCode={} message={}",
                                toolName, attempt, responseRetryable, errorCode, message);
                        // A provider-declared failure is already a complete MCP response.
                        // Never turn a known non-retryable provider failure (429/401/403/4xx)
                        // into another invocation. This is especially important for
                        // quota/rate-limit errors, where another call only consumes more quota.
                        if (!responseRetryable) {
                            toolGovernance.recordFailure(toolName);
                            log.warn("mcp.client.no-retry tool={} reason=provider-non-retryable errorCode={} message={}",
                                    toolName, errorCode, message);
                        }
                    }
                }
                log.info("mcp.client.complete tool={} attempt={} durationMs={}", toolName, attempt, elapsedMs(started));
                return result;
            } catch (Exception exception) {
                if (isCancellation(exception)) {
                    Thread.currentThread().interrupt();
                    log.info("mcp.client.cancelled tool={} attempt={} durationMs={}",
                            callback.getToolDefinition().name(), attempt, elapsedMs(started));
                    throw stopException(exception);
                }
                last = exception;
                boolean policyFailure = exception instanceof SecurityException
                        || exception instanceof com.example.travel.exception.ToolApprovalRequiredException;
                if (!policyFailure) {
                    toolGovernance.recordFailure(toolName);
                }
                boolean retryable = !policyFailure && isRetryable(exception);
                log.error("mcp.client.error phase=invocation tool={} attempt={} retryable={} errorType={} errorMessage={} durationMs={}",
                        toolName, attempt, retryable, exception.getClass().getName(),
                        safeExceptionMessage(exception), elapsedMs(started), exception);
                if (!retryable || attempt == maxAttempts) {
                    break;
                }
                log.warn("mcp.client.retry tool={} attempt={} nextAttempt={} errorType={} errorMessage={}",
                        toolName, attempt, attempt + 1, exception.getClass().getName(), safeExceptionMessage(exception));
            }
        }
        throw last;
    }

    private boolean isAllowed(ToolCallback callback) {
        return allowedTools.isEmpty() || allowedTools.contains(callback.getToolDefinition().name());
    }

    private ToolCallback findExact(String toolName) {
        return availableTools().stream()
                .filter(this::isAllowed)
                .filter(callback -> toolName.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MCP server does not expose allowed tool: " + toolName));
    }

    private boolean isRetryable(Exception exception) {
        // A malformed JSON-RPC frame is a protocol/payload failure, not a transient
        // tool failure. Retrying the same malformed response only adds latency and
        // duplicates noisy stack traces; the domain client can move to its fallback.
        String message = exceptionChainMessage(exception).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("error parsing json-rpc message")
                || message.contains("unexpected end-of-input")
                || message.contains("failed to read value")) {
            return false;
        }

        // Provider 4xx/quota failures are deterministic. In particular, a 429 must
        // never be retried by the generic MCP transport loop. The previous version
        // could miss 429 when Spring/MCP wrapped the provider response in another
        // exception type.
        if (isNonRetryableProviderError(message)) {
            return false;
        }

        return !(exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException
                || exception instanceof SecurityException
                || exception instanceof com.example.travel.exception.ToolApprovalRequiredException);
    }

    private static boolean isCancellation(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof GraphStopRequestedException
                    || current instanceof InterruptedException
                    || current instanceof java.util.concurrent.CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static GraphStopRequestedException stopException(Throwable error) {
        return error instanceof GraphStopRequestedException stop
                ? stop
                : new GraphStopRequestedException(error);
    }

    private boolean isRetryableProviderResponse(String errorCode, String message) {
        String code = errorCode == null ? "" : errorCode.toUpperCase(java.util.Locale.ROOT);
        String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return !isNonRetryableProviderError(code + " " + text);
    }

    private boolean isNonRetryableProviderError(String text) {
        String value = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return value.contains("provider_http_429")
                || value.contains("http 429")
                || value.contains("too many requests")
                || value.contains("rate limit")
                || value.contains("rate_limit")
                || value.contains("quota reached")
                || value.contains("quota exceeded")
                || value.contains("provider_http_401")
                || value.contains("http 401")
                || value.contains("provider_http_403")
                || value.contains("http 403")
                || value.contains("provider_http_400")
                || value.contains("http 400")
                || value.contains("provider_http_422")
                || value.contains("http 422")
                || value.contains("provider_http_404")
                || value.contains("http 404");
    }

    private String exceptionChainMessage(Throwable exception) {
        StringBuilder value = new StringBuilder();
        Throwable current = exception;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current.getMessage() != null) {
                value.append(' ').append(current.getMessage());
            }
            value.append(' ').append(current.getClass().getName());
            current = current.getCause();
        }
        return value.toString();
    }

    private String callWithTimeout(ToolCallback callback, String argumentJson) throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            java.util.concurrent.Future<String> future = executor.submit(() -> callback.call(argumentJson));
            try {
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                future.cancel(true);
                throw new java.util.concurrent.TimeoutException("MCP tool timed out after " + timeoutMs + "ms");
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode responseTree(String response) throws Exception {
        JsonNode result = objectMapper.readTree(response);
        if (result.isString()) {
            result = objectMapper.readTree(result.asString());
        }
        if (result.isArray() && !result.isEmpty() && result.get(0).has("text")) {
            result = objectMapper.readTree(result.get(0).path("text").asString());
        }
        if (result.has("content") && result.path("content").isArray()
                && !result.path("content").isEmpty()) {
            JsonNode content = result.path("content").get(0);
            if (content.has("text")) {
                result = objectMapper.readTree(content.path("text").asString());
            }
        }
        return result;
    }

    private String safeExceptionMessage(Throwable exception) {
        if (exception == null) return "";
        String message = exception.getMessage();
        if (message != null && !message.isBlank()) return message.replace("\n", " ");
        Throwable cause = exception.getCause();
        return cause == null ? exception.getClass().getSimpleName() : safeExceptionMessage(cause);
    }

    private String abbreviate(String value) {
        String normalized = value == null ? "" : value.replace("\n", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

}
