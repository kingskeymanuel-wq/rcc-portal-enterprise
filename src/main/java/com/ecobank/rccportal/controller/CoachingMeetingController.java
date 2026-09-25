package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.UserDirectoryResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.CoachingMeetingService;
import com.ecobank.rccportal.service.CoachingMeetingService.*;
import com.ecobank.rccportal.service.TeamLeaderService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Meetings de performance tête-à-tête (Team Leader ↔ agent) — voir CoachingMeetingService. */
@RestController
@RequestMapping("/api/meetings")
public class CoachingMeetingController {

    private final CoachingMeetingService service;
    private final TeamLeaderService teamLeaderService;

    public CoachingMeetingController(CoachingMeetingService service, TeamLeaderService teamLeaderService) {
        this.service = service;
        this.teamLeaderService = teamLeaderService;
    }

    @GetMapping
    public List<Meeting> list(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.list(requester);
    }

    @GetMapping("/{id}")
    public Meeting get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.get(requester, id);
    }

    /** Agents du Team Leader (liste de choix du formulaire). */
    @GetMapping("/agents")
    public List<UserDirectoryResponse> agents(@AuthenticationPrincipal AuthenticatedUser requester) {
        return teamLeaderService.teamMembers(requester);
    }

    @GetMapping("/cc-candidates")
    public List<Person> ccCandidates(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.ccCandidates(requester);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Meeting schedule(@RequestBody ScheduleRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.schedule(requester, body);
    }

    @PutMapping("/{id}/report")
    public Meeting report(@PathVariable Long id, @RequestBody ReportRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.report(requester, id, body);
    }

    @PostMapping("/{id}/acknowledge")
    public Meeting acknowledge(@PathVariable Long id, @RequestBody AckRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.acknowledge(requester, id, body);
    }

    @PostMapping("/{id}/cancel")
    public Meeting cancel(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.cancel(requester, id);
    }
}
