package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TeamLeaderService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/team-leader")
public class TeamLeaderController {

    private final TeamLeaderService teamLeaderService;
    private final com.ecobank.rccportal.service.AlertEngineService alertEngineService;

    public TeamLeaderController(TeamLeaderService teamLeaderService,
                                com.ecobank.rccportal.service.AlertEngineService alertEngineService) {
        this.teamLeaderService = teamLeaderService;
        this.alertEngineService = alertEngineService;
    }

    private void requireTeamLeaderOrAdmin(AuthenticatedUser requester) {
        if (requester == null
                || !("team_leader".equalsIgnoreCase(requester.role()) || "admin".equalsIgnoreCase(requester.role()))) {
            throw ApiException.forbidden("Réservé au Team Leader ou à l'admin.");
        }
    }

    @GetMapping("/my-team")
    public java.util.Map<String, String> myTeam(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        return java.util.Map.of("team", teamLeaderService.requireLedTeam(requester).name());
    }

    @GetMapping("/members")
    public List<UserDirectoryResponse> members(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        return teamLeaderService.teamMembers(requester);
    }

    /** Détails complets (contrat, résidence) — pour la fiche agent éditable de l'onglet Membres. */
    @GetMapping("/members/full")
    public List<com.ecobank.rccportal.dto.UserResponse> membersFull(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        return teamLeaderService.teamMembersFull(requester);
    }

    /** Recherche d'agents éligibles à rejoindre mon équipe — bouton "Ajouter un agent". */
    @GetMapping("/members/candidates")
    public List<UserDirectoryResponse> candidates(@RequestParam(required = false) String q,
                                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        return teamLeaderService.searchAddableAgents(requester, q);
    }

    /** Ajoute un agent existant à mon équipe. */
    @PostMapping("/members/{userId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        teamLeaderService.addMember(requester, userId);
    }

    /** Retire un agent de mon équipe — resigned=true si démission (désactive aussi le compte,
     *  visible immédiatement RH/Superviseur/Reporting). */
    @DeleteMapping("/members/{userId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long userId, @RequestParam(defaultValue = "false") boolean resigned,
                             @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        teamLeaderService.removeMember(requester, userId, resigned);
    }

    @GetMapping("/reporting")
    public List<PerformanceResponse> reporting(@RequestParam(required = false) String month,
                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        YearMonth target = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        return teamLeaderService.teamReporting(requester, target);
    }

    @GetMapping("/attendance")
    public List<AttendanceRecordResponse> attendance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        return teamLeaderService.teamAttendance(requester, date != null ? date : LocalDate.now());
    }

    @GetMapping("/quality-evaluations")
    public List<QualityEvaluationResponse> qualityEvaluations(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        return teamLeaderService.teamQualityEvaluations(requester);
    }

    /** Alertes de performance de SON équipe uniquement — jamais celles des autres équipes (voir AlertEngineService). */
    @GetMapping("/alerts")
    public List<PerformanceAlertResponse> alerts(@RequestParam(required = false) String month,
                                                  @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeaderOrAdmin(requester);
        YearMonth target = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        var team = teamLeaderService.requireLedTeam(requester);
        return alertEngineService.listForMonthAndTeam(target, team);
    }
}
