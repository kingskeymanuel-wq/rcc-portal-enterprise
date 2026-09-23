package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentScheduleRepository extends JpaRepository<AgentSchedule, Integer> {
    Optional<AgentSchedule> findByUserAndWorkDate(User user, LocalDate workDate);
    List<AgentSchedule> findByWorkDate(LocalDate workDate);
    List<AgentSchedule> findByUserAndWorkDateBetweenOrderByWorkDateAsc(User user, LocalDate from, LocalDate to);
    List<AgentSchedule> findByWorkDateBetweenOrderByWorkDateAsc(LocalDate from, LocalDate to);
    /** Sans tri particulier — utilisé pour la validation TL en bloc (decideMonthlyPlanning),
     *  qui filtre ensuite par équipe et statut PENDING côté service. */
    List<AgentSchedule> findByWorkDateBetween(LocalDate from, LocalDate to);
}
