package com.example.travel.service;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Measures external provider service boundaries without tagging metrics with
 * URLs, request arguments, API keys, user data or response payloads.
 */
@Aspect
@Component
public class AgentObservabilityProviderAspect {

    private final AgentObservabilityService observability;

    public AgentObservabilityProviderAspect(AgentObservabilityService observability) {
        this.observability = observability;
    }

    @Around("execution(public * com.example.travel.service.ExternalApiService.*(..))")
    public Object measureProviderCall(ProceedingJoinPoint pjp) throws Throwable {
        long started = System.nanoTime();
        boolean success = false;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } finally {
            observability.recordProviderCall(
                    "travel-external",
                    pjp.getSignature().getName(),
                    (System.nanoTime() - started) / 1_000_000L,
                    success);
        }
    }
}
