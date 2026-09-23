package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Escalade automatique des demandes d'aide agent (accès, outils, matériel, difficulté) restées
 * sans résolution au-delà du délai (72 h par défaut, réglage workflow.support.escalationHours) :
 * vérifié toutes les 15 minutes, voir WorkflowService.escalateOverdueSupportRequests().
 */
@Slf4j
@Component
public class WorkflowEscalationScheduler {

    private final WorkflowService workflowService;

    public WorkflowEscalationScheduler(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void escalate() {
        try {
            int n = workflowService.escalateOverdueSupportRequests();
            if (n > 0) log.warn("⚠ [WORKFLOW] {} demande(s) d'aide escaladée(s) au portail Superviseur.", n);
        } catch (Exception e) {
            log.warn("⚠ [WORKFLOW] Escalade automatique impossible pour l'instant : {}", e.getMessage());
        }
    }
}
