package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.TrainingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrainingProgressRepository extends JpaRepository<TrainingProgress, Integer> {

    Optional<TrainingProgress> findByLesson_LessonIdAndUserId(Integer lessonId, Long userId);

    List<TrainingProgress> findByUserId(Long userId);

    @Query("select p from TrainingProgress p where p.lesson.formation.formationId = :formationId")
    List<TrainingProgress> findAllByFormationId(@Param("formationId") Integer formationId);

    @Query("select p from TrainingProgress p where p.lesson.formation.formationId = :formationId and p.userId = :userId")
    List<TrainingProgress> findByFormationIdAndUserId(@Param("formationId") Integer formationId, @Param("userId") Long userId);
}
