package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.RecordShiftEventRequest;
import com.ecobank.rccportal.dto.ShiftEventResponse;
import com.ecobank.rccportal.dto.ShiftStatusResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ShiftService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Suivi de shift — l'agent ne peut qu'ajouter un événement le concernant
 * (pause/pause déjeuner/fin de shift), jamais modifier/supprimer l'historique.
 * La vue globale par jour est réservée à QA/admin.
 */
@RestController
@RequestMapping("/api/shift")
public class ShiftController {

    private final ShiftService shiftService;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public ShiftController(ShiftService shiftService, com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.shiftService = shiftService;
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ShiftStatusResponse myStatus(@AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftService.getStatus(requester.username());
    }

    /** L'équipe que dirige l'appelant, s'il est Team Leader — sinon null. Utilisé par le front
     *  (Suivi de shift) pour fixer directement la vue d'un Team Leader sur SA équipe sans lui
     *  proposer de sélecteur (RH/Excelliam/Superviseur/Admin/QA gardent le choix, eux). */
    @GetMapping("/me/led-team")
    public java.util.Map<String, String> myLedTeam(@AuthenticationPrincipal AuthenticatedUser requester) {
        String ledTeam = "team_leader".equalsIgnoreCase(requester.role())
                ? userRepository.findFirstByUsernameIgnoreCase(requester.username())
                        .map(com.ecobank.rccportal.model.User::getLedTeam)
                        .orElse(null)
                : null;
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("ledTeam", ledTeam);
        return body;
    }

    /** Les propres événements de l'agent pour un jour donné — sa bande personnelle dans Ma Performance. */
    @GetMapping("/me/events")
    public List<ShiftEventResponse> myEventsForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftService.forDate(requester.username(), date);
    }

    /** Même principe sur une plage — vues semaine/mois de la bande personnelle. */
    @GetMapping("/me/events/range")
    public List<ShiftEventResponse> myEventsForRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftService.forRange(requester.username(), from, to);
    }

    /** Mes jours de congé/absence approuvés sur une plage — grise ma bande personnelle. */
    @GetMapping("/me/leave-days")
    public java.util.Set<LocalDate> myLeaveDays(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftService.approvedLeaveDates(requester.username(), from, to);
    }

    /** Agents en congé/absence approuvé un jour donné — vue globale QA/Admin/RH. */
    @GetMapping("/leave-days")
    public java.util.Set<String> leaveDaysForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisor(requester);
        return shiftService.usersOnApprovedLeave(date);
    }

    /**
     * Shift d'une équipe. Sans "team", scope automatiquement sur SA PROPRE équipe (un
     * conseiller ne voit que la sienne). Avec "team" explicite ET différente de la sienne,
     * réservé à QA/RH/Admin — sinon n'importe quel agent authentifié pouvait demander
     * l'équipe de son choix en changeant simplement ce paramètre dans l'URL.
     */
    @GetMapping("/team")
    public List<ShiftEventResponse> teamShift(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String team,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireOwnTeamOrSupervisor(requester, team);
        return shiftService.forTeam(requester.username(), date, team);
    }

    /** Même principe, sur une plage — vues semaine/mois de l'onglet Équipe. */
    @GetMapping("/team/range")
    public List<ShiftEventResponse> teamShiftRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String team,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireOwnTeamOrSupervisor(requester, team);
        return shiftService.forTeamRange(requester.username(), from, to, team);
    }

    /**
     * Export Excel — bouton "Exporter Excel" du Suivi de shift (jour/semaine/mois/année, Moi-même
     * ou Équipe). Un agent ne doit jamais pouvoir exporter des shifts, même les siens — refusé
     * avant tout, y compris pour team=false. Pour une équipe, mêmes règles d'accès que
     * teamShiftRange/myEventsForRange : "team" n'est autorisé explicitement que pour
     * QA/RH/Superviseur/Admin/Excelliam ou sa propre équipe.
     */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean team,
            @RequestParam(required = false) String teamCode,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        if ("agent".equalsIgnoreCase(requester.role()) || requester.role() == null) {
            throw ApiException.forbidden("L'export Excel du suivi de shift n'est pas accessible au profil Agent.");
        }
        if (team) requireOwnTeamOrSupervisor(requester, teamCode);
        byte[] body = shiftService.exportExcel(requester.username(), team, from, to, teamCode);
        String filename = "suivi-shift-" + from + "_" + to + ".xlsx";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @PostMapping("/me/event")
    public ShiftStatusResponse recordEvent(@RequestBody RecordShiftEventRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftService.recordEvent(requester.username(), request);
    }

    /** Vue globale d'un jour donné, tous les agents — réservée à QA/admin/RH. */
    @GetMapping
    public List<ShiftEventResponse> forDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisor(requester);
        return shiftService.forDate(date);
    }

    /** Même principe, sur une plage — vue "toutes équipes" de Mon Parcours (portail RH). */
    @GetMapping("/range")
    public List<ShiftEventResponse> forRangeAll(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisor(requester);
        return shiftService.forRangeAll(from, to);
    }

    /** Correction manuelle — un agent a oublié de pointer, QA/Admin/RH ajoute l'événement à sa place. */
    @PostMapping("/manual")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ShiftEventResponse recordManual(@RequestBody com.ecobank.rccportal.dto.ManualShiftEventRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisor(requester);
        return shiftService.recordManualEvent(request.username(), request.eventType(), request.occurredAt());
    }

    /**
     * Autorise si aucune équipe explicite n'est demandée (scope automatique sur la sienne côté
     * service), si l'équipe demandée EST la sienne (agent) ou celle qu'il dirige (Team Leader —
     * voir User.ledTeam, distinct de User.activity), ou si l'appelant est QA/RH/Admin/Excelliam.
     * Sinon, refuse — sans ce contrôle, n'importe quel agent authentifié pouvait consulter la
     * présence de n'importe quelle autre équipe en passant simplement son nom en paramètre.
     */
    private void requireOwnTeamOrSupervisor(AuthenticatedUser requester, String requestedTeam) {
        if (requestedTeam == null || requestedTeam.isBlank()) return;

        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isExcelliam = "excelliam".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (isAdmin || isRh || isExcelliam || isQa) return;

        com.ecobank.rccportal.model.User self = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElse(null);
        if (self == null) throw ApiException.forbidden("Vous ne pouvez consulter que la présence de votre propre équipe.");

        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        if (isTeamLeader && self.getLedTeam() != null && self.getLedTeam().equalsIgnoreCase(requestedTeam)) return;

        String ownTeam = self.getActivity();
        if (ownTeam != null && ownTeam.equalsIgnoreCase(requestedTeam)) return;

        throw ApiException.forbidden("Vous ne pouvez consulter que la présence de votre propre équipe.");
    }

    /** Statut EN DIRECT de chaque agent — pour le bouton "En direct" du suivi de shift.
     *  Admin/RH/QA/Superviseur/Excelliam voient tout ; Team Leader ne voit que sa propre équipe. */
    @GetMapping("/live")
    public List<com.ecobank.rccportal.dto.LiveShiftStatusResponse> live(@AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isExcelliam = "excelliam".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh && !isExcelliam && !isSupervisor && !isQa && !isTeamLeader) {
            throw ApiException.forbidden("Réservé à QA, RH, Excelliam, Superviseur, Team Leader ou administrateur.");
        }

        List<com.ecobank.rccportal.dto.LiveShiftStatusResponse> all = shiftService.liveStatusForAllUsers();
        if (isAdmin || isRh || isExcelliam || isSupervisor || isQa) return all;

        // Team Leader — uniquement sa propre équipe.
        String myTeam = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .map(com.ecobank.rccportal.model.User::getLedTeam)
                .orElse(null);
        if (myTeam == null) return List.of();
        return all.stream().filter(s -> myTeam.equalsIgnoreCase(s.team())).toList();
    }

    private void requireSupervisor(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isExcelliam = "excelliam".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isQa = (requester.service() != null && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' ')))
                || (requester.service() != null && requester.service().toLowerCase().replace("_", " ").equals("quality assurance"));
        if (!isAdmin && !isRh && !isExcelliam && !isSupervisor && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance, Human Resources, Excelliam, a Supervisor, or an administrator can manage team-wide shift tracking.");
        }
    }
}