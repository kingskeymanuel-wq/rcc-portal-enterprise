package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.QualityEvaluation;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QualityEvaluationRepository extends JpaRepository<QualityEvaluation, Integer> {
    List<QualityEvaluation> findByAgentOrderByEvaluationDateDesc(User agent);
    List<QualityEvaluation> findAllByOrderByEvaluationDateDesc();
}
