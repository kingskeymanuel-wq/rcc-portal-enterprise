package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.SyncStatusResponse;
import com.ecobank.rccportal.model.ActionAuditLog;
import com.ecobank.rccportal.repository.ActionAuditLogRepository;
import com.ecobank.rccportal.repository.ManualKpiEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Statut de synchronisation. Architecture du RCC Portal : aucun cache nulle part —
 * ReportingService et tous les portails (RH/Superviseur/Team Leader/QA/Admin) relisent la
 * base en direct à chaque appel, donc il n'existe pas de "resynchronisation" à déclencher
 * au sens propre (rien n'est jamais désynchronisé). Ce service répond plutôt à la vraie
 * question opérationnelle : "les données sont-elles à jour ?" — quand a eu lieu le dernier
 * import, combien aujourd'hui, et un signal d'alerte si aucun import récent n'a eu lieu.
 */
@Service
public class SyncService {

    private final ActionAuditLogRepository auditLogRepository;
    private final ManualKpiEntryRepository manualKpiEntryRepository;

    public SyncService(ActionAuditLogRepository auditLogRepository, ManualKpiEntryRepository manualKpiEntryRepository) {
        this.auditLogRepository = auditLogRepository;
        this.manualKpiEntryRepository = manualKpiEntryRepository;
    }

    @Transactional(readOnly = true)
    public SyncStatusResponse status() {
        LocalDateTime lastKpi = latestOf("IMPORT_EXCEL_KPI");
        LocalDateTime lastSchedule = latestOf("IMPORT_EXCEL_SCHEDULE");
        LocalDateTime lastAny = maxOf(lastKpi, lastSchedule);

        LocalDate today = LocalDate.now();
        long importsToday = List.of("IMPORT_EXCEL_KPI", "IMPORT_EXCEL_SCHEDULE").stream()
                .flatMap(action -> auditLogRepository.findTop200ByActionOrderByCreatedAtDesc(action).stream())
                .filter(l -> l.getCreatedAt().toLocalDate().equals(today))
                .count();

        // Alerte de fraîcheur : aucun import KPI depuis plus de 10 jours — repère simple, pas
        // une règle métier stricte (chaque équipe importe à son propre rythme), juste un signal.
        boolean stale = lastKpi == null || lastKpi.isBefore(LocalDateTime.now().minusDays(10));

        return new SyncStatusResponse(lastKpi, lastSchedule, lastAny,
                manualKpiEntryRepository.count(), importsToday, stale);
    }

    private LocalDateTime latestOf(String action) {
        List<ActionAuditLog> logs = auditLogRepository.findTop200ByActionOrderByCreatedAtDesc(action);
        return logs.isEmpty() ? null : logs.get(0).getCreatedAt();
    }

    private LocalDateTime maxOf(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
