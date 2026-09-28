package com.example.travel.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Measures the two main agent boundaries without changing graph/node
 * constructors: the public planning API and the production execution wave.
 */
@Aspect
@Component
public class AgentObservabilityAspect {

    private final AgentObservabilityService observability;

    public AgentObservabilityAspect(AgentObservabilityService observability) {
        this.observability = observability;
    }

    @Around("execution(* com.example.travel.agent.TravelPlannerAgentService.createTravelPlan(..))")
    public Object observePlan(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint, "createTravelPlan");
    }

    @Around("execution(* com.example.travel.agent.TravelPlannerAgentService.startTravelPlan(..))")
    public Object observeStart(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint, "startTravelPlan");
    }

    @Around("execution(* com.example.travel.graph.ProductionExecutionNode.apply(..))")
    public Object observeExecutionWave(ProceedingJoinPoint joinPoint) throws Throwable {
        long started = System.nanoTime();
        String outcome = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable error) {
            outcome = "error";
            throw error;
        } finally {
            observability.recordAgentNode("production_execution_wave", outcome, elapsedMs(started));
        }
    }

    private Object observe(ProceedingJoinPoint joinPoint, String operation) throws Throwable {
        long started = System.nanoTime();
        String outcome = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable error) {
            outcome = "error";
            throw error;
        } finally {
            observability.recordAgentRun(operation, outcome, elapsedMs(started));
        }
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
