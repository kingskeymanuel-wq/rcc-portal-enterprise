package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AgentScheduleResponse;
import com.ecobank.rccportal.dto.LatenessResponse;
import com.ecobank.rccportal.dto.ScheduleImportResult;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ScheduleService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/** Planning des agents (grille mensuelle par équipe, ou ancien format heure de début) + retards. */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScheduleImportResult importExcel(@RequestParam("file") MultipartFile file,
                                            @RequestParam(required = false) String serviceCode,
                                            @RequestParam(required = false) String countryCode,
                                            @RequestParam(required = false) String team,
                                            @RequestParam(required = false) String month,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisorOrAdmin(requester);
        YearMonth parsedMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : null;
        return scheduleService.importFromExcel(file, serviceCode, countryCode, team, parsedMonth, requester.username());
    }

    @GetMapping("/lateness")
    public List<LatenessResponse> lateness(@RequestParam(required = false) String date,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisorOrAdmin(requester);
        LocalDate targetDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return scheduleService.latenessForDate(targetDate);
    }

    /** Mon propre planning — chaque agent voit le sien, pas besoin d'être QA/Admin. */
    @GetMapping("/me")
    public List<AgentScheduleResponse> myPlanning(@RequestParam String from, @RequestParam String to,
                                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        return scheduleService.planningForUser(requester.username(), LocalDate.parse(from), LocalDate.parse(to));
    }

    /** Planning d'une équipe — onglets Équipe du portail (Team Leader/Superviseur/RH/Admin/QA).
     *  team vide = toutes les équipes confondues, mais uniquement pour QA/RH/Superviseur/Admin —
     *  un Team Leader ou un Agent est toujours restreint à sa propre équipe côté serveur, voir
     *  ScheduleService.planningForTeamScoped(). */
    @GetMapping("/team")
    public List<AgentScheduleResponse> teamPlanning(@RequestParam String from, @RequestParam String to,
                                                      @RequestParam(required = false) String team,
                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        return scheduleService.planningForTeamScoped(requester, team, LocalDate.parse(from), LocalDate.parse(to));
    }

    /** Planification directe par shift (modale "Planifier" — portail Excelliam) : clic équipe,
     *  agents cochés, un shift au choix par agent (7h-16h/8h-17h/12h-21h/21h-6h), Enregistrer.
     *  Écrit dans la même table AgentSchedule que l'import fichier RH — visible immédiatement
     *  dans tous les portails qui affichent déjà le planning, sans synchronisation à part. */
    @PostMapping("/planify")
    public com.ecobank.rccportal.dto.PlanifyShiftsResult planify(@RequestBody com.ecobank.rccportal.dto.PlanifyShiftsRequest request,
                                                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisorOrAdmin(requester);
        return scheduleService.planifyShifts(request);
    }

    /** Le Team Leader valide ou refuse (avec motif obligatoire) le planning en attente de
     *  SA PROPRE équipe sur une période — réservé au rôle Team Leader (chacun ne peut décider
     *  que pour l'équipe qu'il dirige, vérifié côté service via User.ledTeam). */
    @PostMapping("/team/decide")
    public java.util.Map<String, Integer> decideTeamPlanning(@RequestBody com.ecobank.rccportal.dto.MonthlyPlanningDecisionRequest request,
                                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        if (!"team_leader".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only a Team Leader can validate or reject their team's planning.");
        }
        int count = scheduleService.decideMonthlyPlanning(requester.username(), request.from(), request.to(), request.approve(), request.reason());
        return java.util.Map.of("entriesDecided", count);
    }

    /** Étape 1 du flux symétrique — le Team Leader planifie lui-même sa propre équipe (comme le
     *  fait Excelliam via /planify) et l'envoie à Excelliam pour validation. Toujours restreint
     *  côté service à SA PROPRE équipe. */
    @PostMapping("/team/submit")
    public com.ecobank.rccportal.dto.PlanifyShiftsResult submitTeamPlanning(@RequestBody com.ecobank.rccportal.dto.PlanifyShiftsRequest request,
                                                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        return scheduleService.submitTeamPlanning(requester, request);
    }

    /** Étape 2 — Excelliam valide ou refuse le planning soumis par un Team Leader. Une
     *  validation ne le rend pas encore visible aux agents : voir /team/publish. */
    @PostMapping("/excelliam/decide")
    public java.util.Map<String, Integer> decideExcelliamValidation(@RequestBody com.ecobank.rccportal.dto.ExcelliamPlanningDecisionRequest request,
                                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        int count = scheduleService.decideExcelliamValidation(requester, request.team(), request.from(), request.to(), request.approve(), request.reason());
        return java.util.Map.of("entriesDecided", count);
    }

    /** Étape 3 — clic "Mise à jour" du Team Leader : rend effectif (globalement visible)
     *  tout planning déjà validé par Excelliam pour sa propre équipe sur la période donnée. */
    @PostMapping("/team/publish")
    public java.util.Map<String, Integer> publishTeamPlanning(@RequestParam String from, @RequestParam String to,
                                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        int count = scheduleService.publishTeamPlanning(requester, LocalDate.parse(from), LocalDate.parse(to));
        return java.util.Map.of("entriesPublished", count);
    }

    private void requireSupervisorOrAdmin(AuthenticatedUser requester) {
        boolean allowed = "admin".equalsIgnoreCase(requester.role())
                || "rh".equalsIgnoreCase(requester.role())
                || "excelliam".equalsIgnoreCase(requester.role())
                || "supervisor".equalsIgnoreCase(requester.role())
                || (requester.service() != null
                    && Set.of("quality assurance", "superviseur qa").contains(requester.service().toLowerCase().replace('_', ' ')));
        if (!allowed) {
            throw ApiException.forbidden("Only Quality Assurance, RH, Excelliam, Supervisor, or an administrator can manage schedules.");
        }
    }
}

