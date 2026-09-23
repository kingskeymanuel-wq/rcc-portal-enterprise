package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ProcedureZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcedureZoneRepository extends JpaRepository<ProcedureZone, Integer> {
    Optional<ProcedureZone> findByCode(String code);
}
