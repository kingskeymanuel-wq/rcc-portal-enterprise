package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.ActionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActionAuditLogRepository extends JpaRepository<ActionAuditLog, Integer> {
    List<ActionAuditLog> findTop200ByOrderByCreatedAtDesc();
    List<ActionAuditLog> findTop200ByActionOrderByCreatedAtDesc(String action);
    long countByAction(String action);
    long count();
}
