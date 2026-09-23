package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProcedureStepRepository extends JpaRepository<ProcedureStep, Integer> {
    List<ProcedureStep> findByProcedureOrderByStepNumberAsc(Procedure procedure);
    List<ProcedureStep> findByProcedureIn(Collection<Procedure> procedures);
    void deleteByProcedure(Procedure procedure);
}
