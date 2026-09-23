package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.SalesJourneyRequest;
import com.ecobank.rccportal.dto.SalesJourneyResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SalesJourneyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sales-journeys")
public class SalesJourneyController {

    private final SalesJourneyService service;

    public SalesJourneyController(SalesJourneyService service) {
        this.service = service;
    }

    /** Parcours actifs — utilisé par le tableau de bord Outbound (tout agent connecté). */
    @GetMapping
    public List<SalesJourneyResponse> list() {
        return service.listActive();
    }

    /** Tous les parcours, actifs ou non — panneau d'administration (QA/Admin/Team Leader Outbound). */
    @GetMapping("/admin")
    public List<SalesJourneyResponse> listAdmin(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.listAllForAdmin(requester);
    }

    @PostMapping
    public SalesJourneyResponse create(@RequestBody SalesJourneyRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.create(requester, request);
    }

    @PutMapping("/{id}")
    public SalesJourneyResponse update(@PathVariable Integer id, @RequestBody SalesJourneyRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.update(requester, id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.delete(requester, id);
    }
}
