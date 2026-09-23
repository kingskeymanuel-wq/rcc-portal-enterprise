package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.util.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Gestion des rôles, des services du portail (RccService/SERVICES), et de leur
 * attribution aux utilisateurs — module Administration. Distinct de UserService
 * (qui gère les comptes eux-mêmes : approbation, matricule, statut).
 */
@Slf4j
@Service
public class AdministrationService {

    private final RoleRepository roleRepository;
    private final RccServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final KnowledgeArticleRepository knowledgeArticleRepository;
    private final ProcedureRepository procedureRepository;
    private final CourseRepository courseRepository;
    private final WorkflowRequestRepository workflowRequestRepository;
    private final com.ecobank.rccportal.repository.RccNotificationRepository rccNotificationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserFeaturePermissionRepository userFeaturePermissionRepository;

    public AdministrationService(
            RoleRepository roleRepository,
            RccServiceRepository serviceRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            UserServiceAssignmentRepository userServiceAssignmentRepository,
            KnowledgeArticleRepository knowledgeArticleRepository,
            ProcedureRepository procedureRepository,
            CourseRepository courseRepository,
            WorkflowRequestRepository workflowRequestRepository,
            com.ecobank.rccportal.repository.RccNotificationRepository rccNotificationRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserProfileRepository userProfileRepository,
            UserFeaturePermissionRepository userFeaturePermissionRepository) {
        this.roleRepository = roleRepository;
        this.serviceRepository = serviceRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.rccNotificationRepository = rccNotificationRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.knowledgeArticleRepository = knowledgeArticleRepository;
        this.procedureRepository = procedureRepository;
        this.courseRepository = courseRepository;
        this.workflowRequestRepository = workflowRequestRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userProfileRepository = userProfileRepository;
        this.userFeaturePermissionRepository = userFeaturePermissionRepository;
    }

    /**
     * Supprime définitivement un compte utilisateur — pensé pour nettoyer les doublons créés
     * par erreur (import roster, double création manuelle...). Retire d'abord tout ce qui
     * dépend directement de son identité (rôles, services, sessions actives, photo/profil,
     * permissions par fonctionnalité), puis le compte lui-même.
     * Ne tente PAS de nettoyer l'historique d'activité (évaluations QA, KPI, tâches,
     * demandes de workflow...) — si ce compte en a déjà, la contrainte d'intégrité de la base
     * bloque la suppression et remonte une erreur claire plutôt qu'un nettoyage partiel
     * silencieux : dans ce cas, désactiver le compte (voir UserService.setAccountEnabled) est
     * le bon geste, pas le supprimer.
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));

        userRoleRepository.deleteAll(userRoleRepository.findRolesByUserId(userId));
        userServiceAssignmentRepository.deleteAll(userServiceAssignmentRepository.findServicesByUserId(userId));
        refreshTokenRepository.deleteByUser_Id(userId);
        userProfileRepository.findByUser(user).ifPresent(userProfileRepository::delete);
        userFeaturePermissionRepository.deleteAll(userFeaturePermissionRepository.findByUser(user));

        try {
            userRepository.delete(user);
            userRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("user_has_activity_data",
                    "Impossible de supprimer ce compte : il a déjà des données associées ailleurs dans le portail " +
                    "(évaluations QA, KPI, tâches, demandes de workflow...). Désactivez-le plutôt depuis sa fiche.");
        }

        log.warn("⚠ Utilisateur supprimé définitivement (userId={}, username={})", userId, user.getUsername());
    }

    /**
     * Rattache à leur équipe tous les comptes actuellement "Non classée" (TeamClassifier.classify
     * renvoie OTHER) mais qui ont déjà un service opérationnel reconnu (voir
     * SERVICE_CODE_TO_ACTIVITY) — rattrapage pour les comptes créés/attribués avant que
     * assignService() ne le fasse automatiquement. N'écrase jamais une équipe déjà renseignée.
     * Retourne le nombre de comptes effectivement corrigés.
     */
    @Transactional
    public int reclassifyUnclassifiedUsers() {
        int updated = 0;
        for (User user : userRepository.findAll()) {
            if (user.getActivity() != null && !user.getActivity().isBlank()) continue; // déjà classé, on ne touche pas
            List<UserServiceAssignment> services = userServiceAssignmentRepository.findServicesByUserId(user.getId());
            String activity = services.stream()
                    .map(s -> s.getService() != null ? s.getService().getCode() : null)
                    .filter(java.util.Objects::nonNull)
                    .map(code -> SERVICE_CODE_TO_ACTIVITY.get(code.toUpperCase()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            if (activity != null) {
                user.setActivity(activity);
                userRepository.save(user);
                updated++;
            }
        }
        if (updated > 0) log.warn("⚠ Rattrapage équipe : {} compte(s) sorti(s) de \"Non classée\".", updated);
        return updated;
    }

    /**
     * Détecte les doublons probables — même nom complet normalisé (espaces/casse ignorés).
     * Ne supprime rien lui-même : renvoie des groupes pour que l'admin choisisse lequel garder
     * (voir DELETE /api/admin/users/{id} pour la suppression proprement dite). Un nom vide
     * n'est jamais considéré comme un doublon (trop de faux positifs).
     */
    @Transactional(readOnly = true)
    public List<List<UserResponse>> findDuplicateUsers() {
        java.util.Map<String, List<User>> byNormalizedName = new java.util.LinkedHashMap<>();
        for (User user : userRepository.findAll()) {
            String name = user.getName() != null ? user.getName().trim().replaceAll("\\s+", " ").toUpperCase() : "";
            if (name.isBlank()) continue;
            byNormalizedName.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(user);
        }
        return byNormalizedName.values().stream()
                .filter(group -> group.size() > 1)
                .map(group -> group.stream().map(this::toUserResponseForDuplicateCheck).toList())
                .toList();
    }

    private UserResponse toUserResponseForDuplicateCheck(User user) {
        return new UserResponse(
                user.getId() != null ? user.getId().intValue() : null,
                user.getUsername(), user.getName(), user.getEmail(), null, null,
                user.getAffiliateBranch(), Boolean.TRUE.equals(user.getAccountEnabled()), user.getGender(),
                user.getContractType(), user.getContractStatus(), user.getContractStartDate(), user.getContractEndDate(),
                user.getActivity(), null, user.getResidencePlace(), user.getLedTeam());
    }

    // ── Rôles ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll().stream().map(this::toRoleResponse).toList();
    }

    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw ApiException.badRequest("Role name is required.");
        }
        if (roleRepository.existsByNameIgnoreCase(name)) {
            throw ApiException.badRequest("A role with this name already exists.");
        }
        Role role = Role.builder().name(name).description(request.description()).build();
        role = roleRepository.save(role);
        log.info("Role created (name={})", role.getName());
        return toRoleResponse(role);
    }

    // ── Services du portail ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RccServiceResponse> listServices() {
        return serviceRepository.findAll().stream().map(this::toServiceResponse).toList();
    }

    @Transactional
    public RccServiceResponse createService(CreateServiceRequest request) {
        String code = request.code() == null ? "" : request.code().trim();
        String name = request.name() == null ? "" : request.name().trim();
        if (code.isBlank() || name.isBlank()) {
            throw ApiException.badRequest("Service code and name are required.");
        }
        if (serviceRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.badRequest("A service with this code already exists.");
        }
        RccService service = RccService.builder()
                .name(name)
                .description(request.description())
                .path(request.path())
                .code(code)
                .icon(request.icon())
                .color(request.color())
                .status("En service")
                .enabled(request.enabled() == null || request.enabled())
                .displayOrder(request.displayOrder() == null ? 0 : request.displayOrder())
                .openInNewTab(Boolean.TRUE.equals(request.openInNewTab()))
                .portalApp(request.portalApp() == null || request.portalApp())
                .proxyCode(request.proxyCode())
                .build();
        service = serviceRepository.save(service);
        log.info("Portal service created (code={})", service.getCode());
        return toServiceResponse(service);
    }

    /**
     * Décompte d'utilisation par service — à consulter avant toute consolidation, jamais
     * de suppression sans que l'admin ait vu l'impact réel (articles, procédures, etc.).
     */
    @Transactional(readOnly = true)
    public List<ServiceUsageResponse> serviceUsageReport() {
        List<RccService> services = serviceRepository.findAll();
        List<KnowledgeArticle> articles = knowledgeArticleRepository.findAll();
        List<Procedure> procedures = procedureRepository.findAll();
        List<Course> courses = courseRepository.findAll();
        List<WorkflowRequest> workflowRequests = workflowRequestRepository.findAll();
        List<UserServiceAssignment> assignments = userServiceAssignmentRepository.findAll();

        return services.stream().map(s -> new ServiceUsageResponse(
                s.getId(), s.getCode(), s.getName(),
                articles.stream().filter(a -> a.getService() != null && a.getService().getId().equals(s.getId())).count(),
                procedures.stream().filter(p -> p.getService() != null && p.getService().getId().equals(s.getId())).count(),
                courses.stream().filter(c -> c.getService() != null && c.getService().getId().equals(s.getId())).count(),
                workflowRequests.stream().filter(w -> w.getRelatedService() != null && w.getRelatedService().getId().equals(s.getId())).count(),
                assignments.stream().filter(a -> a.getService() != null && a.getService().getId().equals(s.getId())).count()
        )).toList();
    }

    /**
     * Consolide tous les services vers un service unique "RCC" — le crée s'il n'existe pas,
     * réassigne toutes les références (articles, procédures, formations, demandes de workflow,
     * assignations utilisateur) des autres services vers lui, PUIS supprime les services
     * devenus orphelins. Jamais de suppression avant réassignation complète — pas de perte
     * de données, seulement un regroupement. Réservé admin (vérifié côté contrôleur).
     */
    @Transactional
    public ServiceConsolidationResult consolidateServicesToRcc() {
        RccService rcc = serviceRepository.findByCodeIgnoreCase("RCC")
                .orElseGet(() -> serviceRepository.save(RccService.builder()
                        .name("RCC").code("RCC").enabled(true).displayOrder(0)
                        .openInNewTab(false).portalApp(true).status("En service").build()));

        List<RccService> others = serviceRepository.findAll().stream()
                .filter(s -> !s.getId().equals(rcc.getId())).toList();
        if (others.isEmpty()) {
            return new ServiceConsolidationResult(rcc.getCode(), List.of(), 0, 0, 0, 0, 0);
        }
        java.util.Set<Long> otherIds = others.stream().map(RccService::getId).collect(java.util.stream.Collectors.toSet());

        long articlesReassigned = 0;
        for (KnowledgeArticle a : knowledgeArticleRepository.findAll()) {
            if (a.getService() != null && otherIds.contains(a.getService().getId())) {
                a.setService(rcc);
                knowledgeArticleRepository.save(a);
                articlesReassigned++;
            }
        }
        long proceduresReassigned = 0;
        for (Procedure p : procedureRepository.findAll()) {
            if (p.getService() != null && otherIds.contains(p.getService().getId())) {
                p.setService(rcc);
                procedureRepository.save(p);
                proceduresReassigned++;
            }
        }
        long coursesReassigned = 0;
        for (Course c : courseRepository.findAll()) {
            if (c.getService() != null && otherIds.contains(c.getService().getId())) {
                c.setService(rcc);
                courseRepository.save(c);
                coursesReassigned++;
            }
        }
        long workflowRequestsReassigned = 0;
        for (WorkflowRequest w : workflowRequestRepository.findAll()) {
            if (w.getRelatedService() != null && otherIds.contains(w.getRelatedService().getId())) {
                w.setRelatedService(rcc);
                workflowRequestRepository.save(w);
                workflowRequestsReassigned++;
            }
        }
        long userAssignmentsReassigned = 0;
        for (UserServiceAssignment ua : userServiceAssignmentRepository.findAll()) {
            if (ua.getService() != null && otherIds.contains(ua.getService().getId())) {
                boolean alreadyHasRcc = userServiceAssignmentRepository
                        .existsByUserIdAndServiceId(ua.getUser().getId(), rcc.getId());
                if (alreadyHasRcc) {
                    userServiceAssignmentRepository.delete(ua); // évite un doublon d'assignation
                } else {
                    ua.setService(rcc);
                    userServiceAssignmentRepository.save(ua);
                }
                userAssignmentsReassigned++;
            }
        }

        List<String> deletedCodes = others.stream().map(RccService::getCode).toList();
        serviceRepository.deleteAll(others);
        log.warn("Consolidation des services : {} service(s) supprimé(s) ({}), tout réassigné vers RCC.",
                others.size(), deletedCodes);

        return new ServiceConsolidationResult(rcc.getCode(), deletedCodes,
                articlesReassigned, proceduresReassigned, coursesReassigned,
                workflowRequestsReassigned, userAssignmentsReassigned);
    }

    /**
     * Supprime un service précis — retire d'abord toutes les affectations d'utilisateurs
     * (UserServiceAssignment) qui pointent dessus, pour éviter une contrainte de clé
     * étrangère bloquante. Les articles/procédures/formations/demandes liés ne sont PAS
     * supprimés, juste détachés (mis à null) — leur contenu reste intact.
     */
    @Transactional
    public void deleteService(Long serviceId) {
        RccService service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> ApiException.notFound("Service inconnu."));

        userServiceAssignmentRepository.findAll().stream()
                .filter(a -> a.getService() != null && a.getService().getId().equals(serviceId))
                .forEach(userServiceAssignmentRepository::delete);

        knowledgeArticleRepository.findAll().stream()
                .filter(a -> a.getService() != null && a.getService().getId().equals(serviceId))
                .forEach(a -> { a.setService(null); knowledgeArticleRepository.save(a); });
        procedureRepository.findAll().stream()
                .filter(p -> p.getService() != null && p.getService().getId().equals(serviceId))
                .forEach(p -> { p.setService(null); procedureRepository.save(p); });
        courseRepository.findAll().stream()
                .filter(c -> c.getService() != null && c.getService().getId().equals(serviceId))
                .forEach(c -> { c.setService(null); courseRepository.save(c); });
        workflowRequestRepository.findAll().stream()
                .filter(w -> w.getRelatedService() != null && w.getRelatedService().getId().equals(serviceId))
                .forEach(w -> { w.setRelatedService(null); workflowRequestRepository.save(w); });

        serviceRepository.delete(service);
        log.warn("Service supprimé : {} ({})", service.getCode(), service.getName());
    }

    // ── Réclamations (alertes remontées depuis la page de connexion) ────

    private static final java.util.Set<String> LOGIN_ALERT_SUBJECTS = java.util.Set.of(
            "Compte verrouillé", "Mot de passe oublié", "Demande d'assistance connexion");

    /**
     * Alertes remontées depuis la page de connexion — dédupliquées : une même alerte crée
     * une notification par admin en base (voir AuthService.alertItAdmins), regroupées ici
     * en une seule entrée par contenu identique, du plus récent au plus ancien.
     */
    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.LoginAlertResponse> loginAlerts() {
        List<com.ecobank.rccportal.model.RccNotification> matching = rccNotificationRepository.findAll().stream()
                .filter(n -> LOGIN_ALERT_SUBJECTS.stream().anyMatch(subj -> n.getContent() != null && n.getContent().startsWith("[" + subj + "]")))
                .toList();

        java.util.Map<String, List<com.ecobank.rccportal.model.RccNotification>> byContent = matching.stream()
                .collect(java.util.stream.Collectors.groupingBy(com.ecobank.rccportal.model.RccNotification::getContent));

        return byContent.values().stream()
                .map(group -> {
                    com.ecobank.rccportal.model.RccNotification first = group.get(0);
                    String content = first.getContent();
                    String subject = LOGIN_ALERT_SUBJECTS.stream()
                            .filter(s -> content.startsWith("[" + s + "]"))
                            .findFirst().orElse("Alerte");
                    LocalDateTime earliest = group.stream().map(com.ecobank.rccportal.model.RccNotification::getCreatedAt)
                            .min(LocalDateTime::compareTo).orElse(first.getCreatedAt());
                    List<Integer> ids = group.stream().map(com.ecobank.rccportal.model.RccNotification::getNotificationId).toList();
                    return new com.ecobank.rccportal.dto.LoginAlertResponse(
                            subject, content, earliest,
                            first.getActionType() != null, first.getActionType(), first.getActionTarget(), ids);
                })
                .sorted(java.util.Comparator.comparing(com.ecobank.rccportal.dto.LoginAlertResponse::createdAt).reversed())
                .toList();
    }

    /** Retire une alerte de la liste (toutes ses copies, une par admin) — "marquer comme traitée". */
    @Transactional
    public void resolveLoginAlert(List<Integer> notificationIds) {
        rccNotificationRepository.deleteAllById(notificationIds);
    }

    // ── Attribution des rôles à un utilisateur ──────────────────────────

    @Transactional(readOnly = true)
    public List<RoleResponse> listUserRoles(Long userId) {
        return userRoleRepository.findRolesByUserId(userId).stream()
                .map(ur -> toRoleResponse(ur.getRole()))
                .toList();
    }

    @Transactional
    public void assignRole(Long userId, Long roleId) {
        if (userRoleRepository.existsByUser_IdAndRole_Id(userId, roleId)) {
            return; // déjà attribué, idempotent
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ApiException.notFound("Unknown role."));
        userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        log.info("Role assigned (userId={}, role={})", userId, role.getName());
    }

    @Transactional
    public void removeRole(Long userId, Long roleId) {
        List<UserRole> matches = userRoleRepository.findByUser_Id(userId).stream()
                .filter(ur -> ur.getRole() != null && roleId.equals(ur.getRole().getId()))
                .toList();
        userRoleRepository.deleteAll(matches);
        log.info("Role(s) removed (userId={}, roleId={}, count={})", userId, roleId, matches.size());
    }

    /**
     * Rôles VITAUX au système de connexion — jamais supprimés par le nettoyage ci-dessous,
     * quoi que l'admin demande. AuthService.getPrimaryRole() cherche explicitement ces noms
     * exacts (comparaison insensible à la casse) pour déterminer le rôle du JWT ; les
     * supprimer déconnecterait immédiatement tous les admins/RH/superviseurs/team leaders
     * du portail, sans recours.
     */
    private static final java.util.Set<String> PROTECTED_ROLE_NAMES = java.util.Set.of(
            "admin", "rh", "excelliam", "supervisor", "team_leader", "agent");

    /**
     * Supprime tous les rôles de dbo.ROLES SAUF ceux de la liste à conserver (insensible à
     * la casse) et les 5 rôles protégés ci-dessus. Supprime d'abord toutes les attributions
     * (USER_ROLES) des rôles visés, pour ne jamais violer la contrainte de clé étrangère.
     * Opération destructive et volontairement PAS automatique au démarrage — déclenchée une
     * seule fois, à la demande explicite d'un admin, via un bouton dédié.
     */
    @Transactional
    public int cleanupRolesExcept(List<String> namesToKeep) {
        java.util.Set<String> keepLower = namesToKeep.stream()
                .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());

        List<Role> toDelete = roleRepository.findAll().stream()
                .filter(r -> r.getName() != null)
                .filter(r -> !PROTECTED_ROLE_NAMES.contains(r.getName().toLowerCase()))
                .filter(r -> !keepLower.contains(r.getName().toLowerCase()))
                .toList();

        for (Role role : toDelete) {
            userRoleRepository.deleteByRole_Id(role.getId());
        }
        roleRepository.deleteAll(toDelete);
        log.warn("[ADMIN] Nettoyage des rôles — {} rôle(s) supprimé(s), {} protégé(s)/conservé(s).",
                toDelete.size(), roleRepository.count());
        return toDelete.size();
    }

    // ── Attribution des services à un utilisateur ───────────────────────

    @Transactional(readOnly = true)
    public List<RccServiceResponse> listUserServices(Long userId) {
        return userServiceAssignmentRepository.findServicesByUserId(userId).stream()
                .map(us -> toServiceResponse(us.getService()))
                .toList();
    }

    /**
     * Codes de service (dbo.SERVICES.CODE) qui correspondent chacun à une équipe dirigée
     * (User.LED_TEAM, valeurs de TeamClassifier.Team) — voir requireLedTeam() dans
     * TeamLeaderService, utilisé par /api/team-leader/my-team pour scoper tout le contenu du
     * portail Team Leader (membres, reporting, ventes...) à la bonne équipe. Attribuer le
     * service positionne donc automatiquement LED_TEAM, sans étape admin séparée : les trois
     * "portails" Team Leader Inbound Voice / Inbound Mail / Outbound sont en réalité la même
     * page (/team-leader), dont le contenu change entièrement selon cette valeur.
     */
    private static final java.util.Map<String, String> SERVICE_CODE_TO_LED_TEAM = java.util.Map.of(
            "TEAM_LEADER_INBOUND_VOICE", "INBOUND_VOICE",
            "TEAM_LEADER_INBOUND_MAIL", "INBOUND_MAIL",
            "TEAM_LEADER_OUTBOUND", "OUTBOUND"
    );

    /**
     * Codes de service qui correspondent chacun à une équipe opérationnelle reconnue par
     * TeamClassifier.classify(User.activity) — voir ce fichier pour les mots-clés attendus
     * (ex. "VOICE"/"INBOUND" → Inbound Voix, "MAIL" → Inbound Mail/Rafiki). Attribuer un de
     * ces services positionne donc automatiquement le champ Équipe, pour qu'un agent/Team
     * Leader n'apparaisse jamais "Non classée" faute d'une étape admin séparée. Les services
     * de management/QA (RH, Superviseur, Quality Assurance...) n'y figurent pas volontairement
     * : ce ne sont pas des équipes opérationnelles, et ils sont de toute façon exclus des
     * écrans de reporting KPI (voir ReportingService.isExcludedFromKpi()).
     */
    private static final java.util.Map<String, String> SERVICE_CODE_TO_ACTIVITY = java.util.Map.of(
            "AGENT_INBOUND", "INBOUND VOICE",
            "TEAM_LEADER_INBOUND_VOICE", "INBOUND VOICE",
            "AGENT_INBOUND_MAIL", "INBOUND MAIL",
            "TEAM_LEADER_INBOUND_MAIL", "INBOUND MAIL",
            "AGENT_CIB", "CIB",
            "AGENT_OUTBOUND", "OUTBOUND",
            "TEAM_LEADER_OUTBOUND", "OUTBOUND"
    );

    @Transactional
    public void assignService(Long userId, Long serviceId) {
        if (userServiceAssignmentRepository.existsByUserIdAndServiceId(userId, serviceId)) {
            return; // déjà attribué, idempotent
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        RccService service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> ApiException.notFound("Unknown service."));
        userServiceAssignmentRepository.save(
                UserServiceAssignment.builder().user(user).service(service).build());
        log.info("Service assigned (userId={}, service={})", userId, service.getCode());

        String ledTeam = service.getCode() != null
                ? SERVICE_CODE_TO_LED_TEAM.get(service.getCode().toUpperCase()) : null;
        if (ledTeam != null && !ledTeam.equals(user.getLedTeam())) {
            user.setLedTeam(ledTeam);
            userRepository.save(user);
            log.info("LED_TEAM auto-défini à '{}' suite à l'attribution du service {} (userId={})",
                    ledTeam, service.getCode(), userId);
        }

        // N'écrase JAMAIS une équipe déjà renseignée (elle peut refléter un import roster réel
        // plus précis que ce mapping générique) — ne comble que le champ vide, pour sortir
        // l'agent de "Non classée" sans risquer de corrompre une donnée déjà correcte.
        String activity = service.getCode() != null
                ? SERVICE_CODE_TO_ACTIVITY.get(service.getCode().toUpperCase()) : null;
        if (activity != null && (user.getActivity() == null || user.getActivity().isBlank())) {
            user.setActivity(activity);
            userRepository.save(user);
            log.info("Équipe (activity) auto-définie à '{}' suite à l'attribution du service {} (userId={})",
                    activity, service.getCode(), userId);
        }

        // Le Superviseur QA supervise les écoutes/évaluations/formations — il doit donc pouvoir
        // les effectuer lui aussi (voir QualityEvaluationController, TrainingApiController...,
        // qui vérifient le service "Quality Assurance"). Attribuer ce seul service lui donne
        // donc automatiquement la base Quality Assurance en plus, sans étape admin séparée.
        if ("SUPERVISEUR_QA".equalsIgnoreCase(service.getCode())) {
            serviceRepository.findByCodeIgnoreCase("QUALITY_ASSURANCE").ifPresent(qaService -> {
                if (!userServiceAssignmentRepository.existsByUserIdAndServiceId(userId, qaService.getId())) {
                    userServiceAssignmentRepository.save(
                            UserServiceAssignment.builder().user(user).service(qaService).build());
                    log.info("Service Quality Assurance auto-attribué suite à l'attribution de Superviseur QA (userId={})", userId);
                }
            });
        }
    }

    @Transactional
    public void removeService(Long userId, Long serviceId) {
        List<UserServiceAssignment> matches = userServiceAssignmentRepository.findByUserId(userId).stream()
                .filter(us -> us.getService() != null && serviceId.equals(us.getService().getId()))
                .toList();
        userServiceAssignmentRepository.deleteAll(matches);
        log.info("Service(s) removed (userId={}, serviceId={}, count={})", userId, serviceId, matches.size());

        // Si le service retiré est un des 3 services Team Leader ci-dessus, et que LED_TEAM
        // correspond encore exactement à ce qu'il avait positionné, on l'efface — sans jamais
        // toucher à un LED_TEAM positionné autrement (ex. manuellement par l'admin).
        matches.stream()
                .map(UserServiceAssignment::getService)
                .filter(java.util.Objects::nonNull)
                .map(RccService::getCode)
                .filter(java.util.Objects::nonNull)
                .map(String::toUpperCase)
                .map(SERVICE_CODE_TO_LED_TEAM::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(ledTeam -> {
                    User user = userRepository.findById(userId).orElse(null);
                    if (user != null && ledTeam.equals(user.getLedTeam())) {
                        user.setLedTeam(null);
                        userRepository.save(user);
                        log.info("LED_TEAM réinitialisé suite au retrait du service Team Leader (userId={})", userId);
                    }
                });
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    private RoleResponse toRoleResponse(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getDescription());
    }

    private RccServiceResponse toServiceResponse(RccService s) {
        return new RccServiceResponse(s.getId(), s.getName(), s.getDescription(), s.getPath(),
                s.getCode(), s.getIcon(), s.getColor(), s.getStatus(), s.getEnabled(),
                s.getDisplayOrder(), s.getOpenInNewTab(), s.getPortalApp(), s.getProxyCode());
    }
}