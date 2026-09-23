package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.TeamKpiEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TeamKpiEntryRepository extends JpaRepository<TeamKpiEntry, Integer> {
    List<TeamKpiEntry> findByTeamOrderByPeriodDateDesc(String team);
    List<TeamKpiEntry> findByTeamAndPeriodDateBetweenOrderByPeriodDateAsc(String team, LocalDate from, LocalDate to);
    long deleteByImportBatchId(String importBatchId);
}
