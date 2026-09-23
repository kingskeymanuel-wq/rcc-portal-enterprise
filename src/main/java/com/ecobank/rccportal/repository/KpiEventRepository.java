package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.KpiEvent;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KpiEventRepository extends JpaRepository<KpiEvent, Integer> {

    List<KpiEvent> findByUserAndOccurredAtBetweenOrderByOccurredAtDesc(User user, LocalDateTime from, LocalDateTime to);

    @Query("SELECT e.eventType AS eventType, COUNT(e) AS total FROM KpiEvent e "
         + "WHERE e.user = :user AND e.occurredAt BETWEEN :from AND :to GROUP BY e.eventType")
    List<EventTypeCount> countByEventTypeForUser(User user, LocalDateTime from, LocalDateTime to);

    interface EventTypeCount {
        String getEventType();
        long getTotal();
    }
}
