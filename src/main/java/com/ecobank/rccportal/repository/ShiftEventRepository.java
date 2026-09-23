package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ShiftEvent;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShiftEventRepository extends JpaRepository<ShiftEvent, Long> {

    List<ShiftEvent> findByOccurredAtBetweenOrderByUser_UsernameAscOccurredAtAsc(
            LocalDateTime from, LocalDateTime to);

    List<ShiftEvent> findByUserAndOccurredAtBetweenOrderByOccurredAtAsc(
            User user, LocalDateTime from, LocalDateTime to);

    @Query("""
            SELECT COUNT(DISTINCT CAST(e.occurredAt AS date))
            FROM ShiftEvent e
            WHERE e.user = :user
              AND e.eventType = 'LOGIN'
              AND e.occurredAt BETWEEN :from AND :to
            """)
    long countDistinctLoginDays(@Param("user") User user,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);
}