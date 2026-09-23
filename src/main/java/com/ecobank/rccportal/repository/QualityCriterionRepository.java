package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.QualityCriterion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QualityCriterionRepository extends JpaRepository<QualityCriterion, Integer> {
    List<QualityCriterion> findAllByOrderBySortOrderAsc();
}
