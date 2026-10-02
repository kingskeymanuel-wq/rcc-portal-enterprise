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
    private final com.ecobank.rccportal.service.EffectiveAccessService effectiveAccessService;
    private final com.ecobank.rccportal.service.AdminHierarchyService hierarchyService;
    private final com.ecobank.rccportal.service.DataPatchService dataPatchService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.ecobank.rccportal.service.AuditLogService auditLogService;
    private final com.ecobank.rccportal.service.AccessLevelService accessLevelService;

    public AdministrationController(AdministrationService administrationService,
                                    UserFeaturePermissionService userFeaturePermissionService,
                                    CurrentUserAccessService currentUserAccessService,
                                    com.ecobank.rccportal.service.EffectiveAccessService effectiveAccessService,
                                    com.ecobank.rccportal.service.AdminHierarchyService hierarchyService,
                                    com.ecobank.rccportal.service.DataPatchService dataPatchService,
                                    com.ecobank.rccportal.service.AccessLevelService accessLevelService) {
        this.hierarchyService = hierarchyService;
        this.dataPatchService = dataPatchService;
        this.accessLevelService = accessLevelService;
        this.administrationService = administrationService;
        this.userFeaturePermissionService = userFeaturePermissionService;
        this.currentUserAccessService = currentUserAccessService;
        this.effectiveAccessService = effectiveAccessService;
    }

    /** Organigramme : Superviseur, RH, Head QA et QA, Team Leaders et agents par équipe — rôles et services de chacun. */
    @GetMapping("/hierarchy")
    public com.ecobank.rccportal.service.AdminHierarchyService.Hierarchy hierarchy(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return hierarchyService.hierarchy();
    }

    // ───────────── Base de données : tables des comptes, éditées sans SQL Server ─────────────

    @org.springframework.beans.factory.annotation.Autowired
    private com.ecobank.rccportal.service.AdminDataService adminDataService;

    @GetMapping("/data")
    public List<java.util.Map<String, String>> dataTables(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return adminDataService.tables();
    }

    @GetMapping("/data/options")
    public java.util.Map<String, List<java.util.Map<String, Object>>> dataOptions(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return adminDataService.options();
    }

    @GetMapping("/data/{table}")
    public com.ecobank.rccportal.service.AdminDataService.Page dataRows(@PathVariable String table,
                                                                          @RequestParam(required = false) String q,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "100") int size,
                                                                          @RequestParam(required = false) String sort,
                                                                          @RequestParam(defaultValue = "false") boolean desc,
                                                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return adminDataService.rows(table, q, page, size, sort, desc);
    }

    public record CellUpdate(String column, Object value) {}

    @PatchMapping("/data/{table}/{id}")
    public java.util.Map<String, Object> dataUpdate(@PathVariable String table, @PathVariable long id, @RequestBody CellUpdate body,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return adminDataService.update(table, id, body.column(), body.value(), requester.username());
    }

    @PostMapping("/data/{table}")
    public java.util.Map<String, Object> dataInsert(@PathVariable String table, @RequestBody java.util.Map<String, Object> values,
                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return adminDataService.insert(table, values, requester.username());
    }

    @DeleteMapping("/data/{table}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dataDelete(@PathVariable String table, @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        adminDataService.delete(table, id, requester.username());
    }

    @PostMapping("/data/links/clean")
    public java.util.Map<String, Integer> dataCleanLinks(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return java.util.Map.of("removed", adminDataService.removeDuplicateLinks(requester.username()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.ecobank.rccportal.service.DbHealthService dbHealthService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.ecobank.rccportal.service.OfflineDiagnosticService offlineDiagnosticService;

    /** Fonctionnement sans Internet : état des services locaux et des services Internet encore actifs. */
    @GetMapping("/offline-diagnostic")
    public com.ecobank.rccportal.service.OfflineDiagnosticService.Report offlineDiagnostic(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return offlineDiagnosticService.run();
    }

    /** Contrôle en direct : base utilisée, droits SQL, test d'écriture réel (annulé), données qui faussent les accès. */
    @GetMapping("/db-health")
    public com.ecobank.rccportal.service.DbHealthService.Report dbHealth(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return dbHealthService.check(requester.username());
    }

    /** Correction proposée par le contrôle : liaisons en double / orphelines, équipes menées invalides. */
    @PostMapping("/db-health/fix/{action}")
    public java.util.Map<String, Integer> dbHealthFix(@PathVariable String action, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return switch (action) {
            case "clean-links" -> java.util.Map.of("fixed", adminDataService.removeDuplicateLinks(requester.username()));
            case "clear-led-team" -> {
                int n = dbHealthService.clearInvalidLedTeams();
                auditLogService.record(requester.username(), com.ecobank.rccportal.service.AdminChangeJournal.ACTION,
                        "Base — dbo.USERS : équipe menée invalide effacée sur " + n + " compte(s)");
                yield java.util.Map.of("fixed", n);
            }
            default -> throw com.ecobank.rccportal.util.ApiException.badRequest("Correction inconnue : " + action);
        };
    }

    /** Correctifs de données (planning, rôles…) : appliqués une fois au démarrage, résultat enregistré en base. */
    @GetMapping("/data-patches")
    public List<com.ecobank.rccportal.service.DataPatchService.PatchStatus> dataPatches(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return dataPatchService.list();
    }

    /** Réapplique un correctif (ex. après avoir créé un compte manquant) — écrit en base et journalisé. */
    @PostMapping("/data-patches/{code}/run")
    public java.util.Map<String, String> runDataPatch(@PathVariable String code, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return java.util.Map.of("result", dataPatchService.run(code, requester.username()));
    }

    /** Dernières modifications d'administration enregistrées en base (journal d'audit « ADMINISTRATION »). */
    @GetMapping("/changes")
    public List<AuditLogResponse> recentChanges(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return auditLogService.recent(com.ecobank.rccportal.service.AdminChangeJournal.ACTION);
    }

    public record SetAccessRequest(String level, String team) {}

    /** Accès choisi dans l'organigramme : Agent (éventuellement d'une équipe) ou Team Leader d'une équipe. */
    @PutMapping("/users/{userId}/access")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setAccess(@PathVariable Long userId, @RequestBody SetAccessRequest request,
                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        administrationService.setAccess(userId, request.level(), request.team());
    }

    public record UserIdsRequest(List<Long> userIds) {}

    /** Repasse en agent les comptes Team Leader uniquement par le champ équipe menée (voir AdministrationService). */
    @PostMapping("/hierarchy/clear-led-team")
    public java.util.Map<String, Integer> clearLedTeam(@RequestBody UserIdsRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return java.util.Map.of("fixed", administrationService.clearLedTeamOnly(request.userIds()));
    }

    public record ChangeAccessLevelRequest(String level, String team) {}

    /** Change le niveau d'accès en un geste (Team Leader → Agent, changement d'équipe…) — appliqué à tout le portail. */
    @PutMapping("/users/{userId}/access-level")
    public com.ecobank.rccportal.service.AccessLevelService.Result changeAccessLevel(@PathVariable Long userId,
                                                                                    @RequestBody ChangeAccessLevelRequest request,
                                                                                    @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return accessLevelService.change(userId, request.level(), request.team(), requester == null ? null : requester.username());
    }

    /** Accès effectif d'un utilisateur (profil, équipe menée, portail) et ce qui manque — fiche utilisateur de l'admin. */
    @GetMapping("/users/{userId}/effective-access")
    public com.ecobank.rccportal.service.EffectiveAccessService.EffectiveAccess effectiveAccess(@PathVariable Long userId,
                                                                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return effectiveAccessService.of(userId);
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
            "Agent Inbound", "Agent Inbound Voice", "Agent Outbound", "Agent Réseaux sociaux", "Team Leader Réseaux sociaux",
            "Agent Inbound Mail", "Agent Rafiki", "Agent CIB", "Agent Télévente", "Team Leader Télévente", "Agent Digitalisation", "Team Leader Digitalisation", "Team Leader Rafiki", "Team Leader CIB", "Team Leader",
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