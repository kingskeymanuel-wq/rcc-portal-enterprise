package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.QaTeamActivityService;
import com.ecobank.rccportal.service.TeamPerformanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Performance métier par équipe (Inbound Voix / Mail / CIB / Outbound) et activité de l'équipe QA. */
@RestController
public class TeamPerformanceController {

    private final TeamPerformanceService teamPerformance;
    private final QaTeamActivityService qaTeam;

    public TeamPerformanceController(TeamPerformanceService teamPerformance, QaTeamActivityService qaTeam) {
        this.teamPerformance = teamPerformance;
        this.qaTeam = qaTeam;
    }

    /** team : INBOUND_VOICE | INBOUND_MAIL | CIB | OUTBOUND (ignoré pour un Team Leader : son équipe). */
    @GetMapping("/api/team-performance")
    public TeamPerformanceService.TeamPerformance performance(@AuthenticationPrincipal AuthenticatedUser requester,
                                                               @RequestParam(required = false) String team,
                                                               @RequestParam(required = false) String month,
                                                               @RequestParam(required = false) String from,
                                                               @RequestParam(required = false) String to,
                                                               @RequestParam(required = false) String countryCode) {
        return teamPerformance.performance(requester, team, month, from, to, countryCode);
    }

    @GetMapping("/api/qa-team/activity")
    public QaTeamActivityService.TeamActivity qaActivity(@AuthenticationPrincipal AuthenticatedUser requester,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return qaTeam.teamActivity(requester, from, to);
    }

    @GetMapping("/api/qa-team/members/{username}/activity")
    public List<QaTeamActivityService.ActivityItem> qaMemberActivity(@AuthenticationPrincipal AuthenticatedUser requester,
                                                                     @PathVariable String username,
                                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return qaTeam.memberActivity(requester, username, from, to);
    }
}
