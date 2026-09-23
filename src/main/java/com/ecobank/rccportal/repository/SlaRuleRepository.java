package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.SlaRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SlaRuleRepository extends JpaRepository<SlaRule, Integer> {

    List<SlaRule> findByIsActiveTrueOrderBySortOrderAscMotifAsc();

    /** Sous-ensemble motif-client historique, sans les activités rattachées à un pôle (voir
     *  RalphSearchService.buildSlaContext — ce contexte RAF ne doit pas se noyer dans les
     *  ~150 lignes d'activités des fiches pôle, de nature différente). */
    List<SlaRule> findByIsActiveTrueAndPoleIsNullOrderBySortOrderAscMotifAsc();

    List<SlaRule> findAllByOrderBySortOrderAscMotifAsc();

    List<SlaRule> findByPole_PoleIdAndIsActiveTrueOrderBySortOrderAscCategoryAscMotifAsc(Integer poleId);

    int countByPole_PoleIdAndIsActiveTrue(Integer poleId);
}
