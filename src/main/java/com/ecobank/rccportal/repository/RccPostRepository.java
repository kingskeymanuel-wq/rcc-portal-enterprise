package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RccPostRepository extends JpaRepository<RccPost, Integer> {
    List<RccPost> findAllByOrderByPublishedAtDesc();
    boolean existsByContentAndPublishedAtAfter(String content, LocalDateTime after);
}
