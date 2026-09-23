package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.TeamRequest;
import com.ecobank.rccportal.dto.TeamResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TeamService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** GET public (voir SecurityConfig) : la liste des équipes doit être visible depuis l'écran
 * de connexion/inscription, avant toute authentification. Création réservée à l'IT/admin. */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    public List<TeamResponse> listActive() {
        return teamService.listActive();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@Valid @RequestBody TeamRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        if (!"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can create a team.");
        }
        return teamService.create(request);
    }

    /** Nombre d'agents actuellement dans chaque équipe — pour repérer celles devenues obsolètes (0 agent) avant de les supprimer. */
    @GetMapping("/usage")
    public java.util.Map<String, Long> usage(@AuthenticationPrincipal AuthenticatedUser requester) {
        if (!"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can view team usage.");
        }
        return teamService.usageByCode();
    }

    /** Supprime une équipe — uniquement si plus aucun agent n'y est rattaché (sécurité contre une suppression accidentelle). */
    @DeleteMapping("/{teamId}")
    public void delete(@PathVariable Integer teamId, @AuthenticationPrincipal AuthenticatedUser requester) {
        if (!"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can delete a team.");
        }
        teamService.delete(teamId);
    }
}
