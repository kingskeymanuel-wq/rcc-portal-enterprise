package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.WorkflowRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRequestRepository extends JpaRepository<WorkflowRequest, Integer> {

    List<WorkflowRequest> findByRequestedByOrderByCreatedAtDesc(User requestedBy);

    List<WorkflowRequest> findByStatusAndAssignedTeamOrderByCreatedAtAsc(String status, String assignedTeam);

    List<WorkflowRequest> findByTypeAndStatus(String type, String status);

    List<WorkflowRequest> findByAssignedTeamOrderByCreatedAtDesc(String assignedTeam);

    List<WorkflowRequest> findByTypeOrderByCreatedAtDesc(String type);

    /** File d'un Team Leader précis — assignedTo (pas assignedTeam générique, voir WorkflowService.decide). */
    List<WorkflowRequest> findByStatusAndAssignedToOrderByCreatedAtAsc(String status, User assignedTo);

    List<WorkflowRequest> findByAssignedToOrderByCreatedAtDesc(User assignedTo);
}