package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.ActionAuditLog;
import com.ecobank.rccportal.repository.ActionAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Journal d'audit — traçabilité obligatoire pour une banque. Chaque action significative
 * (import Excel, suppression d'un import...) est enregistrée ici, jamais modifiée ni
 * supprimée après coup — c'est la garantie qu'aucune donnée ajoutée/modifiée en base ne
 * peut disparaître sans laisser de trace consultable.
 */
@Service
public class AuditLogService {

    private final ActionAuditLogRepository repository;

    public AuditLogService(ActionAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String username, String action, String details) {
        repository.save(ActionAuditLog.builder()
                .username(username != null ? username : "système")
                .action(action)
                .details(details)
                .build());
    }

    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.AuditLogResponse> recent(String actionFilter) {
        List<ActionAuditLog> logs = (actionFilter != null && !actionFilter.isBlank())
                ? repository.findTop200ByActionOrderByCreatedAtDesc(actionFilter.trim().toUpperCase())
                : repository.findTop200ByOrderByCreatedAtDesc();
        return logs.stream()
                .map(l -> new com.ecobank.rccportal.dto.AuditLogResponse(
                        l.getAuditLogId(), l.getUsername(), l.getAction(), l.getDetails(), l.getCreatedAt()))
                .toList();
    }
}
