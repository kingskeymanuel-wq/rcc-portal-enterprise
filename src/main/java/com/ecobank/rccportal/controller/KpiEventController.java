package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.KpiEventCountResponse;
import com.ecobank.rccportal.dto.KpiEventResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.KpiEventService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/kpi/events")
public class KpiEventController {

    private final KpiEventService kpiEventService;

    public KpiEventController(KpiEventService kpiEventService) {
        this.kpiEventService = kpiEventService;
    }

    @GetMapping
    public List<KpiEventResponse> list(
            @RequestParam String matricule,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSelfOrAdmin(requester, matricule);
        return kpiEventService.listForUser(matricule, from, to);
    }

    @GetMapping("/counts")
    public List<KpiEventCountResponse> counts(
            @RequestParam String matricule,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSelfOrAdmin(requester, matricule);
        return kpiEventService.countByTypeForUser(matricule, from, to);
    }

    private void requireSelfOrAdmin(AuthenticatedUser requester, String matricule) {
        if (requester.username().equals(matricule) || "admin".equalsIgnoreCase(requester.role())) return;
        throw ApiException.forbidden("You can only view your own KPI events.");
    }
}
