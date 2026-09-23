package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProcedureRepository extends JpaRepository<Procedure, Integer> {
    List<Procedure> findByZoneOrderByTitleAsc(ProcedureZone zone);
    List<Procedure> findAllByOrderByTitleAsc();
    List<Procedure> findByTitleContainingIgnoreCaseOrderByTitleAsc(String keyword);
}
