package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.GameCompetitionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameCompetitionParticipantRepository extends JpaRepository<GameCompetitionParticipant, Integer> {
    List<GameCompetitionParticipant> findByCompetitionId(Integer competitionId);
    List<GameCompetitionParticipant> findByCompetitionIdAndTeam(Integer competitionId, String team);
    Optional<GameCompetitionParticipant> findByCompetitionIdAndUserId(Integer competitionId, Long userId);
    List<GameCompetitionParticipant> findByUserId(Long userId);
}
