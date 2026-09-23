package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.CoachingPlan;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoachingPlanRepository extends JpaRepository<CoachingPlan, Integer> {
    List<CoachingPlan> findByAgentOrderByDueDateAsc(User agent);
    List<CoachingPlan> findAllByOrderByDueDateAsc();
}
