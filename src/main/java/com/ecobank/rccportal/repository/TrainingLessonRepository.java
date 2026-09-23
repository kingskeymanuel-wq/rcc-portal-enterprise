package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.TrainingLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainingLessonRepository extends JpaRepository<TrainingLesson, Integer> {

    List<TrainingLesson> findByFormation_FormationIdOrderByOrderIndexAsc(Integer formationId);

    long countByFormation_FormationId(Integer formationId);
}
