package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.TrainingFormation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainingFormationRepository extends JpaRepository<TrainingFormation, Integer> {

    List<TrainingFormation> findAllByOrderByScheduledDateAscScheduledTimeAsc();

    List<TrainingFormation> findByTargetTeamIsNullOrTargetTeam(String targetTeam);
}
