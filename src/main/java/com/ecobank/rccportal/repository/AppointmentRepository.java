package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Integer> {

    List<Appointment> findByAgentUserIdOrderByScheduledAtDesc(Long agentUserId);

    List<Appointment> findByAgentUserIdInAndScheduledAtBetweenOrderByScheduledAtAsc(
            List<Long> agentUserIds, LocalDateTime from, LocalDateTime to);

    List<Appointment> findByAgentUserIdAndScheduledAtBetweenOrderByScheduledAtAsc(
            Long agentUserId, LocalDateTime from, LocalDateTime to);
}
