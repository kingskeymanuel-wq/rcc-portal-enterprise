package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.QualityEvaluation;
import com.ecobank.rccportal.model.QualityEvaluationScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface QualityEvaluationScoreRepository extends JpaRepository<QualityEvaluationScore, Integer> {
    List<QualityEvaluationScore> findByEvaluation(QualityEvaluation evaluation);
    List<QualityEvaluationScore> findByEvaluationIn(Collection<QualityEvaluation> evaluations);
    void deleteByEvaluation(QualityEvaluation evaluation);
}
