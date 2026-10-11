package com.example.travel.repository;

import com.example.travel.entity.AgentGoalEvaluation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentGoalEvaluationRepository extends JpaRepository<AgentGoalEvaluation, Long> {
    List<AgentGoalEvaluation> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    long countByUserId(String userId);
    long countByUserIdAndStatus(String userId, String status);


    Page<AgentGoalEvaluation> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, Instant since, Pageable pageable);
    long countByUserIdAndCreatedAtAfter(String userId, Instant since);
    long countByUserIdAndStatusAndCreatedAtAfter(String userId, String status, Instant since);

    @Query("select coalesce(avg(e.score), 0.0) from AgentGoalEvaluation e where e.userId = :userId and e.createdAt >= :since")
    double averageScoreByUserIdAndCreatedAtAfter(@Param("userId") String userId, @Param("since") Instant since);

    @Query(value = "select e from AgentGoalEvaluation e where e.userId = :userId " +
            "and (:since is null or e.createdAt >= :since) " +
            "and (:status is null or e.status = :status) " +
            "and (:query is null or lower(coalesce(e.goal, '')) like lower(:query) " +
            "or lower(coalesce(e.destination, '')) like lower(:query) " +
            "or lower(coalesce(e.threadId, '')) like lower(:query) " +
            "or lower(coalesce(e.reason, '')) like lower(:query) " +
            "or lower(coalesce(e.budget, '')) like lower(:query))",
            countQuery = "select count(e) from AgentGoalEvaluation e where e.userId = :userId " +
            "and (:since is null or e.createdAt >= :since) " +
            "and (:status is null or e.status = :status) " +
            "and (:query is null or lower(coalesce(e.goal, '')) like lower(:query) " +
            "or lower(coalesce(e.destination, '')) like lower(:query) " +
            "or lower(coalesce(e.threadId, '')) like lower(:query) " +
            "or lower(coalesce(e.reason, '')) like lower(:query) " +
            "or lower(coalesce(e.budget, '')) like lower(:query))")
    Page<AgentGoalEvaluation> searchForUser(@Param("userId") String userId,
            @Param("since") Instant since, @Param("status") String status,
            @Param("query") String query, Pageable pageable);

    @Query("select coalesce(avg(e.score), 0.0) from AgentGoalEvaluation e where e.userId = :userId")
    double averageScoreByUserId(@Param("userId") String userId);
}
