package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.AgentDossier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentDossierRepository extends JpaRepository<AgentDossier, Integer> {
}
