package com.example.travel.repository;

import com.example.travel.entity.AgentGoalEvaluation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentGoalEvaluationRepository extends JpaRepository<AgentGoalEvaluation, Long> {
    List<AgentGoalEvaluation> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    long countByUserId(String userId);
    long countByUserIdAndStatus(String userId, String status);

    @Query("select coalesce(avg(e.score), 0.0) from AgentGoalEvaluation e where e.userId = :userId")
    double averageScoreByUserId(@Param("userId") String userId);
}
