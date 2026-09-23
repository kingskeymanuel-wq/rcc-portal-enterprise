package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.DataAnalysisSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataAnalysisSnapshotRepository extends JpaRepository<DataAnalysisSnapshot, Integer> {

    List<DataAnalysisSnapshot> findByPeriodMonthOrderByCreatedAtDesc(String periodMonth);

    List<DataAnalysisSnapshot> findTop20ByOrderByCreatedAtDesc();
}
