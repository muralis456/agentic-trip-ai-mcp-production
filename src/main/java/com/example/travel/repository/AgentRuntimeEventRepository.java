package com.example.travel.repository;

import com.example.travel.entity.AgentRuntimeEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AgentRuntimeEventRepository extends JpaRepository<AgentRuntimeEvent, Long> {
    @Query(value = "select e from AgentRuntimeEvent e where e.userId = :userId " +
            "and (:since is null or e.createdAt >= :since) order by e.createdAt desc",
            countQuery = "select count(e) from AgentRuntimeEvent e where e.userId = :userId " +
            "and (:since is null or e.createdAt >= :since)")
    Page<AgentRuntimeEvent> recentForUser(@Param("userId") String userId,
            @Param("since") Instant since, Pageable pageable);

    @Query("select e.threadId as threadId, count(e.id) as calls, " +
            "sum(case when e.costUsd is not null then 1 else 0 end) as pricedCalls, " +
            "sum(case when e.costUsd is null then 1 else 0 end) as unpricedCalls, " +
            "sum(e.costUsd) as costUsd from AgentRuntimeEvent e " +
            "where e.userId = :userId and e.eventType = 'PROVIDER' " +
            "and (:since is null or e.createdAt >= :since) group by e.threadId")
    java.util.List<AgentRuntimeProviderCostAggregate> providerCostsByThread(
            @Param("userId") String userId, @Param("since") Instant since);

    @Query("select e.eventType as eventType, e.component as component, e.status as status, " +
            "count(e.id) as count, coalesce(avg(e.durationMs), 0) as averageDurationMs, " +
            "coalesce(sum(case when e.attempt > 1 then 1 else 0 end), 0) as retried " +
            "from AgentRuntimeEvent e where e.userId = :userId and (:since is null or e.createdAt >= :since) " +
            "group by e.eventType, e.component, e.status")
    java.util.List<AgentRuntimeEventAggregate> aggregateForUser(
            @Param("userId") String userId, @Param("since") Instant since);
}
