package com.example.travel.repository;

import com.example.travel.entity.TripHistory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripHistoryRepository extends JpaRepository<TripHistory, Long> {
    List<TripHistory> findByUserIdOrderByUpdatedAtDesc(String userId, PageRequest pageable);
    Optional<TripHistory> findByUserIdAndId(String userId, Long id);
    Optional<TripHistory> findByUserIdAndThreadId(String userId, String threadId);
    long deleteByUserIdAndThreadId(String userId, String threadId);
}
