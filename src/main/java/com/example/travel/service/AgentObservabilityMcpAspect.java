package com.example.travel.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Captures MCP client boundary metrics without changing every domain-specific
 * MCP client. It intentionally records only stable operation/tool metadata.
 */
@Aspect
@Component
public class AgentObservabilityMcpAspect {

    private final AgentObservabilityService observability;

    public AgentObservabilityMcpAspect(AgentObservabilityService observability) {
        this.observability = observability;
    }

    @Around("execution(public * com.example.travel.service.McpToolClient.call(..))")
    public Object call(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "call");
    }

    @Around("execution(public * com.example.travel.service.McpToolClient.callByUserInput(..))")
    public Object callByUserInput(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "callByUserInput");
    }

    @Around("execution(public * com.example.travel.service.McpToolClient.invokePreferred(..))")
    public Object invokePreferred(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "invokePreferred");
    }

    @Around("execution(public * com.example.travel.service.McpToolClient.callByCapability(..))")
    public Object callByCapability(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp, "callByCapability");
    }

    private Object measure(ProceedingJoinPoint pjp, String operation) throws Throwable {
        long started = System.nanoTime();
        String tool = operation;
        Object[] args = pjp.getArgs();
        if (args.length > 0 && args[0] instanceof String s && !s.isBlank()) {
            tool = s;
        }
        boolean success = false;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } finally {
            observability.recordMcpCall(tool, operation,
                    (System.nanoTime() - started) / 1_000_000L, success);
        }
    }
}
