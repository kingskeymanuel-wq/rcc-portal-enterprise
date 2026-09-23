package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ProcedureWorkflowNode;
import com.ecobank.rccportal.model.ProcedureWorkflowOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProcedureWorkflowOptionRepository extends JpaRepository<ProcedureWorkflowOption, Integer> {
    List<ProcedureWorkflowOption> findByNode(ProcedureWorkflowNode node);
    List<ProcedureWorkflowOption> findByNodeIn(Collection<ProcedureWorkflowNode> nodes);
    void deleteByNodeIn(Collection<ProcedureWorkflowNode> nodes);
}