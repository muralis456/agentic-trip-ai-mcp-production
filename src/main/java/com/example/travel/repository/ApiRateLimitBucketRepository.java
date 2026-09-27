package com.example.travel.repository;

import com.example.travel.entity.ApiRateLimitBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface ApiRateLimitBucketRepository extends JpaRepository<ApiRateLimitBucket, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ApiRateLimitBucket b where b.userId = :userId")
    Optional<ApiRateLimitBucket> findForUpdate(@Param("userId") String userId);
}
