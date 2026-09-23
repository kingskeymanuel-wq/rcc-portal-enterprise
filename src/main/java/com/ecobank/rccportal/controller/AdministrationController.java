package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AdministrationService;
import com.ecobank.rccportal.service.CurrentUserAccessService;
import com.ecobank.rccportal.service.UserFeaturePermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Gestion des rôles / services du portail / attribution aux utilisateurs — réservé aux administrateurs. */
@RestController
@RequestMapping("/api/admin")
public class AdministrationController {

    private final AdministrationService administrationService;
    private final UserFeaturePermissionService userFeaturePermissionService;
    private final CurrentUserAccessService currentUserAccessService;

    public AdministrationController(AdministrationService administrationService,
                                    UserFeaturePermissionService userFeaturePermissionService,
                                    CurrentUserAccessService currentUserAccessService) {
        this.administrationService = administrationService;
        this.userFeaturePermissionService = userFeaturePermissionService;
        this.currentUserAccessService = currentUserAccessService;
    }

    @GetMapping("/roles")
    public List<RoleResponse> roles(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.listRoles();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(@RequestBody CreateRoleRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.createRole(request);
    }

    @GetMapping("/services")
    public List<RccServiceResponse> services(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.listServices();
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public RccServiceResponse createService(@RequestBody CreateServiceRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.createService(request);
    }

    /** Décompte d'usage par service — à consulter avant toute consolidation. */
    @GetMapping("/services/usage")
    public List<ServiceUsageResponse> servicesUsage(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.serviceUsageReport();
    }

    /**
     * Consolide tous les services vers un seul "RCC" — réassigne tout (articles, procédures,
     * formations, workflow, assignations utilisateur) avant de supprimer les autres services.
     * Irréversible : le frontend doit exiger une confirmation explicite avant cet appel.
     */
    @PostMapping("/services/consolidate-to-rcc")
    public ServiceConsolidationResult consolidateServicesToRcc(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.consolidateServicesToRcc();
    }

    /** Supprime un service précis — irréversible, réservé admin, le frontend doit exiger une confirmation. */
    @DeleteMapping("/services/{serviceId}")
    public void deleteService(@PathVariable Long serviceId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.deleteService(serviceId);
    }

    /** Réclamations remontées depuis la page de connexion (mot de passe oublié, contact IT, compte verrouillé). */
    @GetMapping("/login-alerts")
    public List<com.ecobank.rccportal.dto.LoginAlertResponse> loginAlerts(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.loginAlerts();
    }

    /** Marque une réclamation comme traitée — la retire de la liste (toutes ses copies, une par admin). */
    @PostMapping("/login-alerts/resolve")
    public void resolveLoginAlert(@RequestBody List<Integer> notificationIds, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.resolveLoginAlert(notificationIds);
    }

    @GetMapping("/users/{userId}/roles")
    public List<RoleResponse> userRoles(@PathVariable Long userId,
                                        @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.listUserRoles(userId);
    }

    @PostMapping("/users/{userId}/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignRole(@PathVariable Long userId, @RequestBody AssignRoleRequest request,
                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.assignRole(userId, request.roleId());
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRole(@PathVariable Long userId, @PathVariable Long roleId,
                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.removeRole(userId, roleId);
    }

    /**
     * Nettoyage ponctuel — supprime tous les rôles de dbo.ROLES sauf ceux listés ici (issus
     * de la capture fournie par l'utilisateur + les rôles déjà semés par WorkflowSchemaBootstrap)
     * et les 5 rôles vitaux au système de connexion (protégés quoi qu'il arrive, voir
     * AdministrationService.PROTECTED_ROLE_NAMES). Volontairement PAS automatique au
     * démarrage — un bouton dédié côté Administration, déclenché une seule fois à la demande.
     */
    private static final List<String> ROLES_TO_KEEP = List.of(
            "Agent Inbound", "Agent Outbound", "Team Leader",
            "Team Leader Inbound Voice", "Team Leader Inbound Mail", "Team Leader Outbound",
            "Formateur", "Quality Assurance", "Superviseur Qualité Assurance",
            "Head RCC (Superviseur)", "Head Outbound", "Head CIB-CMB", "Head Resolution"
    );

    @PostMapping("/roles/cleanup")
    public java.util.Map<String, Integer> cleanupRoles(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        int deleted = administrationService.cleanupRolesExcept(ROLES_TO_KEEP);
        return java.util.Map.of("deleted", deleted);
    }

    @GetMapping("/users/{userId}/services")
    public List<RccServiceResponse> userServices(@PathVariable Long userId,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.listUserServices(userId);
    }

    @PostMapping("/users/{userId}/services")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignService(@PathVariable Long userId, @RequestBody AssignServiceRequest request,
                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.assignService(userId, request.serviceId());
    }

    @DeleteMapping("/users/{userId}/services/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeService(@PathVariable Long userId, @PathVariable Long serviceId,
                              @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.removeService(userId, serviceId);
    }

    /** Suppression définitive d'un compte — pensé pour les doublons créés par erreur. Voir
     *  AdministrationService.deleteUser() pour ce qui est nettoyé et ce qui bloque la suppression. */
    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable Long userId, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.deleteUser(userId);
    }

    /** Rattrapage — sort de "Non classée" tout compte ayant déjà un service opérationnel
     *  reconnu. Voir AdministrationService.reclassifyUnclassifiedUsers(). */
    @PostMapping("/users/reclassify-teams")
    public java.util.Map<String, Integer> reclassifyTeams(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return java.util.Map.of("updated", administrationService.reclassifyUnclassifiedUsers());
    }

    /** Détection de doublons (même nom complet) — voir AdministrationService.findDuplicateUsers(). */
    @GetMapping("/users/duplicates")
    public List<List<UserResponse>> duplicateUsers(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return administrationService.findDuplicateUsers();
    }

    @GetMapping("/users/{userId}/permissions")
    public List<FeaturePermissionResponse> userPermissions(@PathVariable Long userId,
                                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return userFeaturePermissionService.listForUser(userId);
    }

    @PutMapping("/users/{userId}/permissions/{featureCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setUserPermission(@PathVariable Long userId, @PathVariable String featureCode,
                                  @RequestBody SetFeaturePermissionRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        userFeaturePermissionService.setPermission(userId, featureCode, request.isAllowed());
    }

    @DeleteMapping("/users/{userId}/permissions/{featureCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearUserPermission(@PathVariable Long userId, @PathVariable String featureCode,
                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        userFeaturePermissionService.clearPermission(userId, featureCode);
    }

    private void requireAdmin(AuthenticatedUser requester) {
        currentUserAccessService.requireAdmin(requester);
    }
}