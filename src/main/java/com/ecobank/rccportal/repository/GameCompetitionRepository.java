package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameCompetitionRepository extends JpaRepository<GameCompetition, Integer> {
    List<GameCompetition> findAllByOrderByCreatedAtDesc();
}
