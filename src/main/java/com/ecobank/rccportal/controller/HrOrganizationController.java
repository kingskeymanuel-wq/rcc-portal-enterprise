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

    public HrOrganizationController(HrOrganizationService hr) {
        this.hr = hr;
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
