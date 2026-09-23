package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.TeamKpiImportResult;
import com.ecobank.rccportal.dto.TeamKpiSeriesResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TeamKpiImportService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * KPI agrégé au niveau équipe/pôle — pour les fichiers type "FOCUS PAR PÔLE" (une ligne par
 * métrique, une colonne par mois, sans agent). Voir TeamKpiImportService pour le détail du
 * pourquoi ce type de fichier ne peut pas alimenter le KPI par agent (ManualKpiEntryService).
 */
@RestController
@RequestMapping("/api/team-kpi")
public class TeamKpiController {

    private final TeamKpiImportService teamKpiImportService;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public TeamKpiController(TeamKpiImportService teamKpiImportService,
                             com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.teamKpiImportService = teamKpiImportService;
        this.userRepository = userRepository;
    }

    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public TeamKpiImportResult importExcel(@RequestParam("file") MultipartFile file,
                                            @RequestParam String team,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireReviewer(requester);
        return teamKpiImportService.importFromExcel(file, team, requester.username());
    }

    /** Historique mensuel d'une métrique — Team Leader restreint automatiquement à sa propre équipe. */
    @GetMapping("/series")
    public TeamKpiSeriesResponse series(@RequestParam String team, @RequestParam String metricCode,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester, team);
        return teamKpiImportService.series(team, metricCode);
    }

    @GetMapping("/metrics")
    public List<String> availableMetrics(@RequestParam String team, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester, team);
        return teamKpiImportService.availableMetrics(team);
    }

    @DeleteMapping("/imports/{importBatchId}")
    public long deleteImportBatch(@PathVariable String importBatchId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireReviewer(requester);
        return teamKpiImportService.deleteImportBatch(importBatchId);
    }

    private void requireReviewer(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isSupervisor && !isQa) {
            throw ApiException.forbidden("Réservé à QA, RH, Superviseur, ou administrateur.");
        }
    }

    /** Comme requireReviewer, mais autorise aussi le Team Leader — uniquement pour SA propre équipe. */
    private void requireCanView(AuthenticatedUser requester, String requestedTeam) {
        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        if (!isTeamLeader) {
            requireReviewer(requester);
            return;
        }
        String myTeam = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .map(com.ecobank.rccportal.model.User::getLedTeam).orElse(null);
        if (myTeam == null || !myTeam.equalsIgnoreCase(requestedTeam)) {
            throw ApiException.forbidden("Vous ne pouvez consulter que les KPI de votre propre équipe.");
        }
    }
}
