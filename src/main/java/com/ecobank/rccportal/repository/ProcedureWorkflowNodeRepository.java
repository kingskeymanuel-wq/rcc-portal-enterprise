package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureWorkflowNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProcedureWorkflowNodeRepository extends JpaRepository<ProcedureWorkflowNode, Integer> {
    List<ProcedureWorkflowNode> findByProcedure(Procedure procedure);
    Optional<ProcedureWorkflowNode> findByProcedureAndIsStartTrue(Procedure procedure);
    void deleteByProcedure(Procedure procedure);
    long countByIsStartTrue();
}