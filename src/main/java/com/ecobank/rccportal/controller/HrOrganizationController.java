package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.HrOrganizationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Portail RH : organisation par filiale / population / équipe, performance et sorties d'agents. */
@RestController
@RequestMapping("/api/hr")
public class HrOrganizationController {

    private final HrOrganizationService hr;
    private final com.ecobank.rccportal.service.HrLiveService live;

    public HrOrganizationController(HrOrganizationService hr, com.ecobank.rccportal.service.HrLiveService live) {
        this.hr = hr;
        this.live = live;
    }

    @GetMapping("/organisation")
    public HrOrganizationService.Organisation organisation(@RequestParam(required = false) String country,
                                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return hr.organisation(requester, country);
    }

    @PutMapping("/organisation/{userId}")
    public Map<String, Boolean> assign(@PathVariable Long userId, @RequestBody AssignRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        hr.assign(requester, userId, body.population(), body.team(), body.countryCode());
        return Map.of("ok", true);
    }

    @GetMapping("/performance")
    public HrOrganizationService.HrPerformance performance(@RequestParam(required = false) String country, @RequestParam(required = false) String month,
                                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return hr.performance(requester, country, month);
    }

    /** Tuiles « Indicateurs RH » et « Centre de contrôle RH » : valeurs réelles de la filiale. */
    @GetMapping("/indicators")
    public com.ecobank.rccportal.service.HrLiveService.Indicators indicators(@RequestParam(required = false) String country,
                                                                            @RequestParam(required = false) String month,
                                                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        return live.indicators(requester, country, month);
    }

    /** Plannings et shifts de toutes les équipes de la filiale, statut en direct (pointeuse) pour aujourd'hui. */
    @GetMapping("/live-planning")
    public com.ecobank.rccportal.service.HrLiveService.LivePlanning livePlanning(@RequestParam(required = false) String country,
                                                                                @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
                                                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        return live.livePlanning(requester, country, date);
    }

    @GetMapping("/departures")
    public List<HrOrganizationService.Departure> departures(@RequestParam(required = false) String country,
                                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        return hr.departures(requester, country);
    }

    @PostMapping("/departures")
    public HrOrganizationService.Departure recordDeparture(@RequestBody DepartureRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return hr.recordDeparture(requester, body.userId(), body.reason(), body.date(), body.comment());
    }

    @PostMapping("/departures/{id}/reintegrate")
    public Map<String, Boolean> reintegrate(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        hr.reintegrate(requester, id);
        return Map.of("ok", true);
    }

    public record AssignRequest(String population, String team, String countryCode) {}

    public record DepartureRequest(Long userId, String reason, LocalDate date, String comment) {}
}
