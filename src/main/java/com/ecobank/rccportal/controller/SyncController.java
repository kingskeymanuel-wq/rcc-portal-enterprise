package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.SyncStatusResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SyncService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /** Fraîcheur des données — dernier import, nombre d'imports aujourd'hui, alerte si aucun
     *  import KPI récent. Réservé QA/RH/Superviseur/Admin (visibilité opérationnelle). */
    @GetMapping("/status")
    public SyncStatusResponse status(@AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isSupervisor && !isQa) {
            throw ApiException.forbidden("Réservé à QA, RH, Superviseur, ou administrateur.");
        }
        return syncService.status();
    }
}
