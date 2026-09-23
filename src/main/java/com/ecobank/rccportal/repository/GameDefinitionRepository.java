package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GameDefinitionRepository extends JpaRepository<GameDefinition, Integer> {
    List<GameDefinition> findAllByOrderBySortOrderAsc();
    List<GameDefinition> findByActiveTrueOrderBySortOrderAsc();
    Optional<GameDefinition> findByGameKey(String gameKey);
}
