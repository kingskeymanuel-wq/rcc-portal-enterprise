package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.QualityCriterion;
import com.ecobank.rccportal.model.QualityCriterionAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface QualityCriterionAttributeRepository extends JpaRepository<QualityCriterionAttribute, Integer> {
    List<QualityCriterionAttribute> findByCriterionOrderBySortOrderAsc(QualityCriterion criterion);
    List<QualityCriterionAttribute> findByCriterionInOrderBySortOrderAsc(Collection<QualityCriterion> criteria);
}
