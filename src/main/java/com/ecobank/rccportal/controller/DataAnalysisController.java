package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.DataAnalysisResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.DataAnalysisService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/data-analysis")
public class DataAnalysisController {

    private final DataAnalysisService dataAnalysisService;
    private final com.ecobank.rccportal.service.AlertEngineService alertEngineService;
    private final com.ecobank.rccportal.service.TeamLeaderService teamLeaderService;
    private final com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository;

    public DataAnalysisController(DataAnalysisService dataAnalysisService,
                                  com.ecobank.rccportal.service.AlertEngineService alertEngineService,
                                  com.ecobank.rccportal.service.TeamLeaderService teamLeaderService,
                                  com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository) {
        this.dataAnalysisService = dataAnalysisService;
        this.alertEngineService = alertEngineService;
        this.teamLeaderService = teamLeaderService;
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * Photos de profil des agents affichés dans les fenêtres de détail (équipe, alertes) :
     * userId → URL de la photo (Mon profil). Les agents sans photo sont simplement absents
     * de la réponse — l'interface affiche alors leurs initiales.
     */
    @GetMapping("/photos")
    public java.util.Map<Long, String> photos(@RequestParam(name = "ids") java.util.List<Long> ids,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        java.util.Set<Long> wanted = new java.util.HashSet<>(ids);
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        if (wanted.isEmpty()) return out;
        for (com.ecobank.rccportal.model.UserProfile p : userProfileRepository.findAll()) {
            if (p.getUser() == null || p.getPhotoUrl() == null || p.getPhotoUrl().isBlank()) continue;
            Long id = p.getUser().getId();
            if (id != null && wanted.contains(id)) out.put(id, p.getPhotoUrl());
        }
        return out;
    }

    /**
     * Qui peut CONSULTER l'analyse (résultat global toutes équipes) : Admin, RH, Superviseur,
     * QA — en plus du Team Leader (scopé à sa propre équipe, voir effectiveTeam()). Générer
     * une nouvelle analyse n'a rien de destructif (relit les vraies données, ajoute une ligne
     * d'historique) — même accès pour tout le monde ici, pas de distinction lecture/écriture.
     */
    private void requireCanView(AuthenticatedUser requester) {
        if (requester == null || !isFullAccessRole(requester) && !isTeamLeader(requester)) {
            throw ApiException.forbidden("Only an administrator, RH, a Supervisor, QA or a Team Leader can view the data analysis.");
        }
    }

    private boolean isFullAccessRole(AuthenticatedUser requester) {
        String role = requester.role();
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        return "admin".equalsIgnoreCase(role) || "rh".equalsIgnoreCase(role) || "supervisor".equalsIgnoreCase(role) || isQa;
    }

    private boolean isTeamLeader(AuthenticatedUser requester) {
        return "team_leader".equalsIgnoreCase(requester.role());
    }

    /** Rôle à accès complet (Admin/RH/Superviseur/QA) : équipe demandée telle quelle (null = toutes).
     *  Team Leader : toujours SA équipe, quoi qu'il demande. */
    private String effectiveTeam(AuthenticatedUser requester, String requestedTeam) {
        if (isFullAccessRole(requester)) return requestedTeam;
        return teamLeaderService.requireLedTeam(requester).name();
    }

    @GetMapping
    public DataAnalysisResponse analyze(@RequestParam(required = false) String month,
                                        @RequestParam(required = false) String team,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        String effectiveTeamCode = effectiveTeam(requester, team);
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            java.time.LocalDate fromDate, toDate;
            try {
                fromDate = java.time.LocalDate.parse(from);
                toDate = java.time.LocalDate.parse(to);
            } catch (Exception e) {
                throw ApiException.badRequest("from/to must be in YYYY-MM-DD format.");
            }
            String label = fromDate.equals(toDate) ? fromDate.toString() : from + " → " + to;
            return dataAnalysisService.analyzeRange(fromDate, toDate, label, effectiveTeamCode, requester.username());
        }
        YearMonth targetMonth;
        try {
            targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        } catch (Exception e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
        return dataAnalysisService.analyze(targetMonth, effectiveTeamCode, requester.username());
    }

    /** Analyses déjà générées pour ce mois (IA ou moteur de règles) — consultables sans relancer le calcul. */
    @GetMapping("/history")
    public java.util.List<com.ecobank.rccportal.model.DataAnalysisSnapshot> history(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        YearMonth targetMonth;
        try {
            targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        } catch (Exception e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
        // Un Team Leader ne doit voir que l'historique de sa propre équipe — jamais celui des autres.
        java.util.List<com.ecobank.rccportal.model.DataAnalysisSnapshot> all = dataAnalysisService.history(targetMonth);
        if (isFullAccessRole(requester)) return all;
        String myTeam = effectiveTeam(requester, null);
        return all.stream().filter(s -> myTeam.equalsIgnoreCase(s.getTeamFilter())).toList();
    }

    /** Liste des équipes ayant des données ce mois — pour peupler le sélecteur d'équipe (accès complet uniquement, un Team Leader n'a qu'une équipe). */
    @GetMapping("/teams")
    public java.util.List<String> listTeams(@RequestParam(required = false) String month,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        YearMonth targetMonth;
        try {
            targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        } catch (Exception e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
        if (!isFullAccessRole(requester)) {
            return java.util.List.of(effectiveTeam(requester, null));
        }
        return dataAnalysisService.listTeamsWithData(targetMonth);
    }

    /** Frise chronologique — évolution des indicateurs sur plusieurs mois, pour une équipe (ou globale). */
    @GetMapping("/timeline")
    public java.util.List<com.ecobank.rccportal.model.DataAnalysisSnapshot> timeline(
            @RequestParam(required = false) String team,
            @RequestParam(required = false, defaultValue = "6") int months,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        return dataAnalysisService.timeline(effectiveTeam(requester, team), Math.min(Math.max(months, 1), 24));
    }

    /** Détail par agent d'une équipe — pour "quel agent a bien/mal marché", accessible en clic depuis le tableau de bord. */
    @GetMapping("/agents")
    public java.util.List<com.ecobank.rccportal.dto.PerformanceResponse> agents(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String team,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        String effectiveTeamCode = effectiveTeam(requester, team);
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            java.time.LocalDate fromDate, toDate;
            try {
                fromDate = java.time.LocalDate.parse(from);
                toDate = java.time.LocalDate.parse(to);
            } catch (Exception e) {
                throw ApiException.badRequest("from/to must be in YYYY-MM-DD format.");
            }
            String label = fromDate.equals(toDate) ? fromDate.toString() : from + " → " + to;
            return dataAnalysisService.agentsForTeam(fromDate, toDate, label, effectiveTeamCode);
        }
        YearMonth targetMonth;
        try {
            targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        } catch (Exception e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
        return dataAnalysisService.agentsForTeam(targetMonth, effectiveTeamCode);
    }

    /** Alertes déjà détectées pour ce mois — voir AlertEngineService. */
    @GetMapping("/alerts")
    public java.util.List<com.ecobank.rccportal.dto.PerformanceAlertResponse> alerts(
            @RequestParam(required = false) String month,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        YearMonth targetMonth;
        try {
            targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        } catch (Exception e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
        java.util.List<com.ecobank.rccportal.dto.PerformanceAlertResponse> all = alertEngineService.listForMonth(targetMonth);
        if (isFullAccessRole(requester)) return all;
        String myTeam = effectiveTeam(requester, null);
        return all.stream().filter(a -> myTeam.equalsIgnoreCase(a.team())).toList();
    }

    /** Historique complet d'un agent — toutes ses alertes depuis toujours, pour voir l'évolution de sa performance. */
    @GetMapping("/alerts/agent/{userId}")
    public java.util.List<com.ecobank.rccportal.dto.PerformanceAlertResponse> agentAlertHistory(
            @PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireCanView(requester);
        return alertEngineService.historyForUser(userId);
    }

    /** Déclenche la détection manuellement (le scheduler la lance déjà chaque nuit à 23h). */
    @PostMapping("/alerts/run")
    public java.util.Map<String, Integer> runAlertDetection(@RequestParam(required = false) String month,
                                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator (IT) can run alert detection.");
        }
        YearMonth targetMonth;
        try {
            targetMonth = (month != null && !month.isBlank()) ? YearMonth.parse(month) : YearMonth.now();
        } catch (Exception e) {
            throw ApiException.badRequest("month must be in YYYY-MM format.");
        }
        return java.util.Map.of("created", alertEngineService.detectForMonth(targetMonth));
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    public void acknowledgeAlert(@PathVariable Integer alertId, @AuthenticationPrincipal AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator (IT) can acknowledge alerts.");
        }
        alertEngineService.acknowledge(alertId, requester.username());
    }
}
