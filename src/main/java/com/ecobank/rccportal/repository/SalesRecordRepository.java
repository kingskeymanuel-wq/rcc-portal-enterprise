package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.SalesRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SalesRecordRepository extends JpaRepository<SalesRecord, Integer> {

    List<SalesRecord> findByAgentUserIdOrderBySaleDateDesc(Long agentUserId);

    List<SalesRecord> findByAgentUserIdInAndSaleDateBetweenOrderBySaleDateDesc(
            List<Long> agentUserIds, LocalDate from, LocalDate to);

    List<SalesRecord> findByAgentUserIdAndSaleDateBetweenOrderBySaleDateDesc(
            Long agentUserId, LocalDate from, LocalDate to);
}
