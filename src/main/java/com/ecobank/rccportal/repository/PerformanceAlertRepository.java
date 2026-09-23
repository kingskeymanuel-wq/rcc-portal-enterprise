package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.PerformanceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PerformanceAlertRepository extends JpaRepository<PerformanceAlert, Integer> {

    List<PerformanceAlert> findByPeriodMonthOrderByCreatedAtDesc(String periodMonth);

    List<PerformanceAlert> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByUserIdAndPeriodMonthAndAlertType(Long userId, String periodMonth, String alertType);
}
