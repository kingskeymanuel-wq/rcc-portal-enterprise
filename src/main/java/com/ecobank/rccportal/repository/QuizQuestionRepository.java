package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.QuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Integer> {

    List<QuizQuestion> findByActiveTrueOrderByCreatedAtDesc();

    List<QuizQuestion> findByCategoryIgnoreCaseAndActiveTrue(String category);

    List<QuizQuestion> findByCategoryIgnoreCaseAndDifficultyIgnoreCaseAndActiveTrue(String category, String difficulty);

    List<QuizQuestion> findByDifficultyIgnoreCaseAndActiveTrue(String difficulty);
}
