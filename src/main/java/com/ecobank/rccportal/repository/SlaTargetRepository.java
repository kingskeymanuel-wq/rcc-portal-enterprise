package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.SlaTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SlaTargetRepository extends JpaRepository<SlaTarget, Integer> {
    List<SlaTarget> findAllByOrderByTeamAscTypeAsc();
    Optional<SlaTarget> findByTeamIgnoreCaseAndTypeIgnoreCase(String team, String type);
}
