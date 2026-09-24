package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.BankBranchResponse;
import com.ecobank.rccportal.dto.CardAgencyDtos.AgencyRow;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AgencyPortalService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Portail Agence — l'agence de l'agent connecté, sa disponibilité cartes et ses coordonnées. */
@RestController
@RequestMapping("/api/agence")
public class AgencyPortalController {

    private final AgencyPortalService service;
    private final com.ecobank.rccportal.service.AtmStatusService atmService;

    public AgencyPortalController(AgencyPortalService service, com.ecobank.rccportal.service.AtmStatusService atmService) {
        this.service = service;
        this.atmService = atmService;
    }

    /** Disponibilité des GAB de toutes les agences d'une filiale (consultation, tout agent connecté). */
    @GetMapping("/atm")
    public List<com.ecobank.rccportal.service.AtmStatusService.AtmStatus> atm(@RequestParam(defaultValue = "CI") String country) {
        return atmService.list(country);
    }

    @PutMapping("/me/atm")
    public com.ecobank.rccportal.service.AtmStatusService.AtmStatus myAtm(@RequestBody com.ecobank.rccportal.service.AtmStatusService.AtmRequest body,
                                                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.updateMyAtm(requester, body);
    }

    @GetMapping("/me")
    public AgencyPortalService.AgencyMe me(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.me(requester, true);
    }

    @PutMapping("/me/agency")
    public AgencyPortalService.AgencyMe chooseAgency(@RequestBody Map<String, Long> body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.chooseMyAgency(requester, body.get("branchId"));
    }

    @PutMapping("/me/availability")
    public AgencyRow availability(@RequestBody AgencyPortalService.AvailabilityRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.updateMyAvailability(requester, body);
    }

    @PutMapping("/me/branch")
    public BankBranchResponse branch(@RequestBody AgencyPortalService.BranchInfoRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.updateMyBranch(requester, body);
    }

    /** Administration : liste des rattachements agent ↔ agence. */
    @GetMapping("/assignments")
    public List<AgencyPortalService.Assignment> assignments(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.assignments(requester);
    }

    /** Administration : rattacher / détacher un agent (branchId null = détacher). */
    @PutMapping("/users/{username}/agency")
    public void assign(@PathVariable String username, @RequestBody Map<String, Long> body, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.assignUser(requester, username, body.get("branchId"));
    }
}
