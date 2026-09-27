package com.example.travel.service;

import com.example.travel.exception.GraphStopRequestedException;
import com.example.travel.security.PromptInjectionGuard;
import com.example.travel.tool.ToolGovernanceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class McpToolClientCancellationTest {
    @Test
    void interruptionCancelsInvocationWithoutRetryingMcpCall() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("search_flights");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenAnswer(invocation -> {
            callbackStarted.countDown();
            new CountDownLatch(1).await();
            return "{}";
        });
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] { callback });
        McpToolClient client = new McpToolClient(provider, new ObjectMapper(),
                mock(McpToolSelector.class), "", 2, 10000,
                mock(PromptInjectionGuard.class), mock(ToolGovernanceService.class));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                client.call("search_flights", Map.of("destination", "Tokyo"));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });

        caller.start();
        assertTrue(callbackStarted.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2000);

        assertFalse(caller.isAlive(), "interrupted MCP call should return promptly");
        assertInstanceOf(GraphStopRequestedException.class, failure.get());
        verify(callback, times(1)).call(anyString());
    }
}
