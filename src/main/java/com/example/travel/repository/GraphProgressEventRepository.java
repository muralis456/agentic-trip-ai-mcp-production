package com.example.travel.repository;

import com.example.travel.entity.GraphProgressEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GraphProgressEventRepository extends JpaRepository<GraphProgressEvent, Long> {
    List<GraphProgressEvent> findTop200ByThreadIdOrderByIdDesc(String threadId);
    List<GraphProgressEvent> findByThreadIdAndIdGreaterThanOrderByIdAsc(String threadId, Long id);
    java.util.Optional<GraphProgressEvent> findTop1ByThreadIdOrderByIdDesc(String threadId);
    long countByThreadId(String threadId);
    void deleteByThreadIdAndIdLessThan(String threadId, Long id);
}
