package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AuditLogResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AuditLogService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** Journal d'audit — 200 entrées les plus récentes, filtrable par type d'action. */
    @GetMapping("/logs")
    public List<AuditLogResponse> logs(@RequestParam(required = false) String action,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh && !isSupervisor) {
            throw ApiException.forbidden("Réservé à RH, Superviseur, ou administrateur.");
        }
        return auditLogService.recent(action);
    }
}
