package com.example.travel.repository;

import com.example.travel.entity.AgentModelUsageEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgentModelUsageEventRepository extends JpaRepository<AgentModelUsageEvent, Long> {
    List<AgentModelUsageEvent> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, Instant since, Pageable pageable);
    long countByUserIdAndCreatedAtAfter(String userId, Instant since);

    @Query("select e.model as model, count(e.id) as calls, " +
            "sum(case when e.status = 'ERROR' then 1 else 0 end) as errors, " +
            "coalesce(sum(e.inputTokens), 0) as inputTokens, " +
            "coalesce(sum(e.outputTokens), 0) as outputTokens, " +
            "coalesce(sum(e.durationMs), 0) as durationMs " +
            "from AgentModelUsageEvent e where e.userId = :userId and e.createdAt >= :since group by e.model")
    List<AgentModelUsageAggregate> aggregateByUserAndCreatedAtAfter(
            @Param("userId") String userId, @Param("since") Instant since);
}