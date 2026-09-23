package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.CreateUserRequest;
import com.ecobank.rccportal.dto.UpdateUserRequest;
import com.ecobank.rccportal.dto.FeaturePermissionResponse;
import com.ecobank.rccportal.dto.PendingAccountResponse;
import com.ecobank.rccportal.dto.RccServiceResponse;
import com.ecobank.rccportal.dto.RoleResponse;
import com.ecobank.rccportal.dto.UserDetailResponse;
import com.ecobank.rccportal.dto.UserDirectoryResponse;
import com.ecobank.rccportal.dto.UserResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AdministrationService;
import com.ecobank.rccportal.service.CurrentUserAccessService;
import com.ecobank.rccportal.service.UserFeaturePermissionService;
import com.ecobank.rccportal.service.UserService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final AdministrationService administrationService;
    private final UserFeaturePermissionService userFeaturePermissionService;
    private final CurrentUserAccessService currentUserAccessService;
    private final com.ecobank.rccportal.service.AuthService authService;

    public UserController(UserService userService, AdministrationService administrationService,
                          UserFeaturePermissionService userFeaturePermissionService,
                          CurrentUserAccessService currentUserAccessService,
                          com.ecobank.rccportal.service.AuthService authService) {
        this.userService = userService;
        this.administrationService = administrationService;
        this.userFeaturePermissionService = userFeaturePermissionService;
        this.currentUserAccessService = currentUserAccessService;
        this.authService = authService;
    }

    /** Annuaire complet — réservé à l'administrateur. */
    @GetMapping
    public List<UserResponse> listAll(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.listAll();
    }

    /** Création directe par un admin — le compte est actif immédiatement, sans passer par l'auto-inscription. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@RequestBody CreateUserRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.createUser(request);
    }

    /** Mes propres dérogations de fonctionnalités — utilisé par le menu pour savoir quoi afficher. */
    @GetMapping("/me/permissions")
    public List<FeaturePermissionResponse> myPermissions(@AuthenticationPrincipal AuthenticatedUser requester) {
        return userFeaturePermissionService.listForUsername(requester.username());
    }

    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable Long userId, @RequestBody UpdateUserRequest request,
                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.updateUser(userId, request);
    }

    /** Édition restreinte (contrat + résidence) — RH/Superviseur/Admin (tout agent), Team
     *  Leader (uniquement sa propre équipe). Voir UpdateHrFieldsRequest pour la portée. */
    @PutMapping("/{userId}/hr-fields")
    public UserResponse updateHrFields(@PathVariable Long userId, @RequestBody com.ecobank.rccportal.dto.UpdateHrFieldsRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        boolean isTeamLeader = "team_leader".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh && !isSupervisor
                && !(isTeamLeader && userService.isOwnTeamMember(requester.username(), userId))) {
            throw ApiException.forbidden("Vous ne pouvez modifier que les informations RH des agents de votre équipe.");
        }
        return userService.updateHrFields(userId, request);
    }

    @PostMapping("/{userId}/enable")
    public UserResponse enable(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.setAccountEnabled(userId, true);
    }

    @PostMapping("/{userId}/disable")
    public UserResponse disable(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.setAccountEnabled(userId, false);
    }

    /** Sessions actives (non expirées) — "gérer les connexions" côté Administration. */
    @GetMapping("/{userId}/sessions")
    public List<com.ecobank.rccportal.dto.ActiveSessionResponse> listSessions(@PathVariable Long userId,
                                                                                @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.listActiveSessions(userId);
    }

    /** Déconnexion forcée — révoque toutes les sessions de cet utilisateur, sur toutes ses machines. */
    @DeleteMapping("/{userId}/sessions")
    public long revokeSessions(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.revokeAllSessions(userId);
    }

    /** Réservé à l'admin — remet à zéro le mot de passe d'un compte Excelliam (mot de passe
     *  oublié, ou rotation de sécurité). La personne recrée son mot de passe à sa prochaine
     *  connexion (voir AuthController.setExcelliamPassword). Ne fonctionne QUE sur un compte
     *  Excelliam — voir AuthService.requestExcelliamPasswordReset. */
    @PostMapping("/{userId}/excelliam-password-reset")
    public java.util.Map<String, String> resetExcelliamPassword(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        authService.requestExcelliamPasswordReset(userId);
        return java.util.Map.of("message", "Mot de passe réinitialisé — l'utilisateur devra en recréer un à sa prochaine connexion.");
    }

    /**
     * Fiche détaillée d'un utilisateur : infos de base + rôles + services attribués,
     * en un seul appel (réutilise AdministrationService, seule source de vérité pour
     * les attributions rôle/service — pas de duplication de logique ici).
     * Réservé à la QA et aux admins, même règle que /api/users.
     */
    @GetMapping("/{id}")
    public UserDetailResponse detail(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);

        UserResponse base = userService.getById(id);
        List<RoleResponse> roles = administrationService.listUserRoles(id);
        List<RccServiceResponse> services = administrationService.listUserServices(id);

        return new UserDetailResponse(
                base.id(),
                base.username(),
                base.fullName(),
                base.email(),
                base.active(),
                roles,
                services,
                base.affiliateBranch(),
                base.gender(),
                base.contractType(),
                base.contractStatus(),
                base.contractStartDate(),
                base.activity(),
                base.photoUrl(),
                base.ledTeam()
        );
    }

    /**
     * Répertoire minimal ouvert à tout utilisateur connecté (pas de restriction QA/admin) —
     * sert à choisir un destinataire dans la messagerie interne (voir ChatController).
     */
    /** Consulté juste après connexion — indique si la configuration initiale filiale/service/équipe reste à faire. */
    @GetMapping("/me/team-status")
    public com.ecobank.rccportal.dto.TeamStatusResponse myTeamStatus(@AuthenticationPrincipal AuthenticatedUser requester) {
        return userService.teamStatus(requester.username());
    }

    /** "Mon Parcours" RH — dates et type de contrat de chaque agent, filtré sur la filiale du RH (Admin/Superviseur voient tout). */
    @GetMapping("/hr/contracts")
    public List<UserResponse> contractsForHr(@AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isRh && !isSupervisor) {
            throw ApiException.forbidden("Only Human Resources, a Supervisor or an administrator can view contract tracking.");
        }
        return userService.listContractsForHr(requester.username(), isAdmin || isSupervisor);
    }

    @GetMapping("/directory")
    public List<UserDirectoryResponse> directory(@RequestParam(required = false) String role) {
        List<UserResponse> users = (role != null && !role.isBlank())
                ? userService.listByRole(role)
                : userService.listAll();
        return users.stream().map(this::toDirectory).toList();
    }

    /** Auto-lookup équipe + Team Leader d'un agent — pour le formulaire "Évaluer un appel" (QA). */
    @GetMapping("/{username}/team-info")
    public com.ecobank.rccportal.dto.AgentTeamInfoResponse teamInfo(@PathVariable String username) {
        return userService.findAgentTeamInfo(username);
    }

    /** Recherche pour MON RCC — pas d'annuaire chargé en entier, uniquement des résultats sur requête. */
    @GetMapping("/search")
    public List<UserDirectoryResponse> search(@RequestParam String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        return userService.search(q).stream().map(this::toDirectory).toList();
    }

    private UserDirectoryResponse toDirectory(UserResponse u) {
        return new UserDirectoryResponse(u.id(), u.username(), u.fullName(), u.email(), u.role(),
                u.service(), u.affiliateBranch(), u.active(), u.activity(), u.photoUrl());
    }

    /**
     * Parcours de connectivité : comptes agent en attente d'autorisation —
     * réservé aux administrateurs (Paramètres IT du frontend).
     */
    @GetMapping("/pending")
    public List<PendingAccountResponse> pending(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userService.listPending();
    }

    @PostMapping("/{matricule}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable String matricule, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        userService.approve(matricule);
    }

    /** Réactive un compte verrouillé après échecs de connexion — utilisé par le bouton "Réactiver" des notifications IT. */
    @PostMapping("/{matricule}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable String matricule, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        userService.unlock(matricule);
    }

    @PostMapping("/{matricule}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable String matricule, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        userService.reject(matricule);
    }

    private void requireAdmin(AuthenticatedUser requester) {
        currentUserAccessService.requireAdmin(requester);
    }

    /**
     * Le service est stocké en base sous forme de code (ex. "QUALITY_ASSURANCE"),
     * pas sous forme de libellé d'affichage ("Quality Assurance") : il faut donc
     * normaliser (underscore -> espace, insensible à la casse) avant de comparer,
     * sinon un vrai compte QA se voit refuser l'accès (même piège déjà rencontré
     * et corrigé côté session.js).
     */

}