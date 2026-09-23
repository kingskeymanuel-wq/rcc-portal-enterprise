package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameCompetitionTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameCompetitionTeamRepository extends JpaRepository<GameCompetitionTeam, Integer> {
    List<GameCompetitionTeam> findByCompetitionId(Integer competitionId);
    Optional<GameCompetitionTeam> findByCompetitionIdAndTeam(Integer competitionId, String team);
}
