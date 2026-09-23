package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.AssignedTask;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssignedTaskRepository extends JpaRepository<AssignedTask, Integer> {
    List<AssignedTask> findByAssignedToOrderByCreatedAtDesc(User assignedTo);
    List<AssignedTask> findByAssignedToTeamCodeOrderByCreatedAtDesc(String teamCode);
    List<AssignedTask> findByCreatedByUserAndAssignedToIsNullAndAssignedToTeamCodeIsNullOrderByCreatedAtDesc(User createdByUser);
    List<AssignedTask> findByCategoryIsNotNullOrderByCreatedAtDesc();
}
