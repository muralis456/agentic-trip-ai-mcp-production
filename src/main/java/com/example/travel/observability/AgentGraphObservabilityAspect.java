package com.example.travel.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Instruments LangGraph node execution without coupling every node to metrics code.
 */
@Aspect
@Component
public class AgentGraphObservabilityAspect {

    private final AgentObservabilityService observability;

    public AgentGraphObservabilityAspect(AgentObservabilityService observability) {
        this.observability = observability;
    }

    @Around("execution(* com.example.travel.graph..*Node.apply(..))")
    public Object observeNode(ProceedingJoinPoint joinPoint) throws Throwable {
        String node = joinPoint.getTarget().getClass().getSimpleName();
        long started = System.nanoTime();
        boolean success = false;
        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            observability.recordNode(node, durationMs, success);
        }
    }
}
