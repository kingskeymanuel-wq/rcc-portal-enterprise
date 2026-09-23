package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CreateWorkflowRequestRequest;
import com.ecobank.rccportal.dto.WorkflowRequestResponse;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.WorkflowRequest;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.WorkflowRequestRepository;
import com.ecobank.rccportal.util.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Moteur de workflow générique à une étape : un agent soumet une demande
 * (congé/absence, changement de procédure, matériel/accès) sur une période
 * (jour/semaine/mois) et l'assigne à une équipe (QA ou ADMIN). Toute personne
 * de cette équipe peut la décider — le "responsable désigné" (assignedTo,
 * rôle RESPONSABLE) est purement informatif : il reçoit une notification
 * prioritaire, mais n'importe qui dans l'équipe garde le droit de trancher.
 */
@Service
public class WorkflowService {

    private static final Set<String> INTERNAL_TYPES = Set.of("LEAVE", "PROCEDURE_CHANGE", "ACCESS", "TEAM_ASSIGNMENT");
    /** TEAM_LEADER ajouté pour LEAVE uniquement — voir submit() : jamais choisi librement par le
     *  demandeur, toujours résolu automatiquement au Team Leader de SA PROPRE équipe (User.activity
     *  -> ledTeam), la demande est bloquée si aucun Team Leader n'est configuré pour cette équipe. */
    private static final Set<String> INTERNAL_TEAMS = Set.of("QA", "ADMIN", "TEAM_LEADER");
    /** Types/équipes internes + tous les motifs/équipes du référentiel client RCC360 (RccMotifCatalog). */
    private static final Set<String> ALLOWED_TYPES = java.util.stream.Stream.concat(
            INTERNAL_TYPES.stream(),
            com.ecobank.rccportal.config.RccMotifCatalog.ENTRIES.stream().map(com.ecobank.rccportal.config.RccMotifCatalog.MotifEntry::code))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> ALLOWED_PERIOD_TYPES = Set.of("DAY", "WEEK", "MONTH");
    private static final Set<String> ALLOWED_TEAMS = java.util.stream.Stream.concat(
            INTERNAL_TEAMS.stream(),
            com.ecobank.rccportal.config.RccMotifCatalog.ENTRIES.stream().map(com.ecobank.rccportal.config.RccMotifCatalog.MotifEntry::teamCode))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private final WorkflowRequestRepository workflowRequestRepository;
    private final UserRepository userRepository;
    private final RccNotificationRepository notificationRepository;
    private final com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;
    private final com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final SiteSettingService siteSettingService;
    private final com.ecobank.rccportal.repository.SlaTargetRepository slaTargetRepository;
    private final com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository;

    public static final String SLA_THRESHOLD_HOURS_KEY = "workflow.sla.thresholdHours";
    private static final int DEFAULT_SLA_THRESHOLD_HOURS = 48;

    public WorkflowService(WorkflowRequestRepository workflowRequestRepository, UserRepository userRepository,
                           RccNotificationRepository notificationRepository,
                           com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository,
                           com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository,
                           SiteSettingService siteSettingService,
                           com.ecobank.rccportal.repository.SlaTargetRepository slaTargetRepository,
                           com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository) {
        this.workflowRequestRepository = workflowRequestRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.siteSettingService = siteSettingService;
        this.slaTargetRepository = slaTargetRepository;
        this.userRoleRepository = userRoleRepository;
    }

    public int getSlaThresholdHours() {
        String raw = siteSettingService.get(SLA_THRESHOLD_HOURS_KEY);
        if (raw == null || raw.isBlank()) return DEFAULT_SLA_THRESHOLD_HOURS;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : DEFAULT_SLA_THRESHOLD_HOURS;
        } catch (NumberFormatException e) {
            return DEFAULT_SLA_THRESHOLD_HOURS;
        }
    }

    public void setSlaThresholdHours(int hours) {
        if (hours <= 0) throw ApiException.badRequest("Le seuil doit être un nombre d'heures positif.");
        siteSettingService.set(SLA_THRESHOLD_HOURS_KEY, String.valueOf(hours));
    }

    /** Seuil effectif pour une équipe + type : règle spécifique si elle existe, sinon le seuil global.
     *  Tolère l'absence de la table SlaTargets (migration 009 pas encore exécutée) pour ne jamais
     *  faire planter tout l'aperçu SLA à cause d'une seule table optionnelle manquante. */
    public int resolveThresholdFor(String team, String type) {
        if (team == null || type == null) return getSlaThresholdHours();
        try {
            return slaTargetRepository.findByTeamIgnoreCaseAndTypeIgnoreCase(team, type)
                    .map(com.ecobank.rccportal.model.SlaTarget::getThresholdHours)
                    .orElseGet(this::getSlaThresholdHours);
        } catch (Exception e) {
            return getSlaThresholdHours();
        }
    }

    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.SlaTargetResponse> listSlaTargets() {
        try {
            List<com.ecobank.rccportal.model.SlaTarget> configured = slaTargetRepository.findAllByOrderByTeamAscTypeAsc();
            return configured.stream()
                    .map(t -> new com.ecobank.rccportal.dto.SlaTargetResponse(t.getSlaTargetId(), t.getTeam(), t.getType(), t.getThresholdHours(), true))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public com.ecobank.rccportal.dto.SlaTargetResponse upsertSlaTarget(com.ecobank.rccportal.dto.SlaTargetRequest request, String requesterUsername) {
        if (request.team() == null || !ALLOWED_TEAMS.contains(request.team())) {
            throw ApiException.badRequest("Équipe invalide.");
        }
        if (request.type() == null || !ALLOWED_TYPES.contains(request.type())) {
            throw ApiException.badRequest("Type de demande invalide.");
        }
        if (request.thresholdHours() == null || request.thresholdHours() <= 0) {
            throw ApiException.badRequest("Le seuil doit être un nombre d'heures positif.");
        }
        Long requesterId = requesterUsername != null
                ? userRepository.findFirstByUsernameIgnoreCase(requesterUsername).map(User::getId).orElse(null)
                : null;
        com.ecobank.rccportal.model.SlaTarget target = slaTargetRepository
                .findByTeamIgnoreCaseAndTypeIgnoreCase(request.team(), request.type())
                .orElseGet(() -> com.ecobank.rccportal.model.SlaTarget.builder()
                        .team(request.team()).type(request.type()).build());
        target.setThresholdHours(request.thresholdHours());
        target.setUpdatedByUserId(requesterId);
        target = slaTargetRepository.save(target);
        return new com.ecobank.rccportal.dto.SlaTargetResponse(target.getSlaTargetId(), target.getTeam(), target.getType(), target.getThresholdHours(), true);
    }

    @Transactional
    public void deleteSlaTarget(Integer slaTargetId) {
        com.ecobank.rccportal.model.SlaTarget target = slaTargetRepository.findById(slaTargetId)
                .orElseThrow(() -> ApiException.notFound("Seuil SLA introuvable."));
        slaTargetRepository.delete(target);
    }

    @Transactional
    public WorkflowRequestResponse submit(String username, CreateWorkflowRequestRequest request) {
        if (request.type() == null || !ALLOWED_TYPES.contains(request.type().toUpperCase())) {
            throw ApiException.badRequest("Unknown workflow type. Allowed: " + ALLOWED_TYPES);
        }
        if (request.title() == null || request.title().isBlank()) {
            throw ApiException.badRequest("Title is required.");
        }
        if (request.periodType() == null || !ALLOWED_PERIOD_TYPES.contains(request.periodType().toUpperCase())) {
            throw ApiException.badRequest("Unknown period type. Allowed: " + ALLOWED_PERIOD_TYPES);
        }
        // Congé/absence a besoin d'une vraie période choisie par le demandeur ; changement de
        // procédure et matériel/accès n'en ont pas besoin — la colonne reste NOT NULL en base,
        // on la remplit avec la date du jour dans ce cas plutôt que de la rendre visible au formulaire.
        boolean needsRealPeriod = "LEAVE".equalsIgnoreCase(request.type());
        LocalDate periodFrom = needsRealPeriod ? request.periodFrom() : java.time.LocalDate.now();
        LocalDate periodTo = needsRealPeriod ? request.periodTo() : java.time.LocalDate.now();
        if (needsRealPeriod && (periodFrom == null || periodTo == null)) {
            throw ApiException.badRequest("periodFrom and periodTo are required.");
        }
        if (periodFrom.isAfter(periodTo)) {
            throw ApiException.badRequest("periodFrom must not be after periodTo.");
        }
        String assignedTeamCode = request.assignedTeam();
        if (assignedTeamCode == null || assignedTeamCode.isBlank()) {
            // Motif du référentiel RCC360 : l'équipe est déjà déterminée par le motif choisi,
            // pas besoin de la faire choisir une seconde fois à l'agent.
            assignedTeamCode = com.ecobank.rccportal.config.RccMotifCatalog.findByCode(request.type())
                    .map(com.ecobank.rccportal.config.RccMotifCatalog.MotifEntry::teamCode)
                    .orElse(null);
        }

        User requester = findUser(username);
        User assignedTo = null;

        if ("LEAVE".equalsIgnoreCase(request.type())) {
            // Congé/absence : JAMAIS un choix libre du demandeur (assignedTeam/assignedToUsername
            // de la requête sont ignorés pour ce type) — toujours résolu automatiquement au Team
            // Leader de SA PROPRE équipe (User.activity -> ledTeam). Bloqué avec un message clair
            // + alerte à tous les admins si aucun Team Leader n'est configuré pour cette équipe,
            // plutôt qu'un repli silencieux vers QA/Admin qui masquerait un trou d'organigramme.
            String agentTeam = requester.getActivity();
            if (agentTeam == null || agentTeam.isBlank()) {
                throw ApiException.badRequest(
                        "Votre équipe n'est pas renseignée sur votre profil — impossible de déterminer votre Team Leader. Contactez un administrateur.");
            }
            assignedTo = findTeamLeaderForTeam(agentTeam);
            if (assignedTo == null) {
                notifyAdminsOfMissingTeamLeader(requester, agentTeam);
                throw ApiException.badRequest(
                        "Aucun Team Leader n'est configuré pour l'équipe \"" + agentTeam + "\" — votre demande de congé ne peut pas être soumise. "
                        + "Un administrateur a été alerté ; réessayez une fois un Team Leader affecté à votre équipe.");
            }
            assignedTeamCode = "TEAM_LEADER";
        } else {
            if (assignedTeamCode == null || !ALLOWED_TEAMS.contains(assignedTeamCode.toUpperCase())) {
                throw ApiException.badRequest("Unknown assigned team. Allowed: " + ALLOWED_TEAMS);
            }
            if (request.assignedToUsername() != null && !request.assignedToUsername().isBlank()) {
                assignedTo = userRepository.findFirstByUsernameIgnoreCase(request.assignedToUsername().trim())
                        .orElseThrow(() -> ApiException.badRequest("Unknown assignedToUsername."));
            }
        }
        final String resolvedAssignedTeam = assignedTeamCode;

        com.ecobank.rccportal.model.RccService relatedService = null;
        if (request.serviceCode() != null && !request.serviceCode().isBlank()) {
            relatedService = rccServiceRepository.findByCodeIgnoreCase(request.serviceCode().trim())
                    .orElseThrow(() -> ApiException.badRequest("Unknown service: " + request.serviceCode()));
        }

        WorkflowRequest entity = WorkflowRequest.builder()
                .type(request.type().toUpperCase())
                .title(request.title().trim())
                .details(request.details())
                .periodType(request.periodType().toUpperCase())
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .assignedTeam(resolvedAssignedTeam.toUpperCase())
                .assignedTo(assignedTo)
                .relatedService(relatedService)
                .requestedBy(requester)
                .status(STATUS_PENDING)
                .build();

        entity = workflowRequestRepository.save(entity);

        if (assignedTo != null) {
            String requesterLabel = requester.getName() != null ? requester.getName() : requester.getUsername();
            RccNotification notification = RccNotification.builder()
                    .targetUser(assignedTo)
                    .content("Nouvelle demande à traiter (" + entity.getTitle() + ") de " + requesterLabel)
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);
        }

        return toResponse(entity);
    }

    /**
     * Congés/absences pour la RH — filtré sur SA PROPRE filiale (un RH de Côte d'Ivoire ne voit
     * que les demandes des agents CI). L'admin, lui, voit tout, quelle que soit sa filiale.
     */
    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listLeaveRequestsForHr(String hrUsername, boolean isAdmin) {
        User hr = findUser(hrUsername);
        List<WorkflowRequest> all = workflowRequestRepository.findByTypeOrderByCreatedAtDesc("LEAVE");
        if (isAdmin || hr.getAffiliateBranch() == null) {
            return all.stream().map(this::toResponse).toList();
        }
        return all.stream()
                .filter(r -> {
                    User safe = safeUser(r.getRequestedBy());
                    return safe != null && hr.getAffiliateBranch().equalsIgnoreCase(safe.getAffiliateBranch());
                })
                .map(this::toResponse)
                .toList();
    }

    /** Congés/absences visibles par un Team Leader — uniquement ceux de SA PROPRE équipe menée
     *  (User.ledTeam), qu'ils lui soient assignés ou déjà décidés (tous statuts, pas que PENDING —
     *  cohérent avec /hr/leave qui montre tout l'historique, pas juste la file à traiter). */
    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listLeaveRequestsForTeamLeader(String teamLeaderUsername) {
        User teamLeader = findUser(teamLeaderUsername);
        if (teamLeader.getLedTeam() == null || teamLeader.getLedTeam().isBlank()) return List.of();
        return workflowRequestRepository.findByTypeOrderByCreatedAtDesc("LEAVE").stream()
                .filter(r -> {
                    User requester = safeUser(r.getRequestedBy());
                    return requester != null && teamLeader.getLedTeam().equalsIgnoreCase(requester.getActivity());
                })
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listMine(String username) {
        User requester = findUser(username);
        return workflowRequestRepository.findByRequestedByOrderByCreatedAtDesc(requester)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listPendingForTeam(String team) {
        return workflowRequestRepository.findByStatusAndAssignedTeamOrderByCreatedAtAsc(STATUS_PENDING, team)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listAllForTeam(String team) {
        return workflowRequestRepository.findByAssignedTeamOrderByCreatedAtDesc(team)
                .stream().map(this::toResponse).toList();
    }

    /** File d'un Team Leader précis — ses propres demandes de congé assignées, pas celles des
     *  autres Team Leaders (contrairement à QA/ADMIN, "TEAM_LEADER" n'est pas une équipe unique). */
    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listPendingForAssignee(String username) {
        User assignee = findUser(username);
        return workflowRequestRepository.findByStatusAndAssignedToOrderByCreatedAtAsc(STATUS_PENDING, assignee)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> listAllForAssignee(String username) {
        User assignee = findUser(username);
        return workflowRequestRepository.findByAssignedToOrderByCreatedAtDesc(assignee)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public WorkflowRequestResponse decide(Integer requestId, String deciderUsername, String deciderTeam,
                                          boolean approve, String comment) {
        WorkflowRequest entity = workflowRequestRepository.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Unknown workflow request."));

        if ("TEAM_LEADER".equals(entity.getAssignedTeam())) {
            // Contrairement à QA/ADMIN (n'importe qui de l'équipe décide), une demande TEAM_LEADER
            // est assignée à UNE personne précise (le Team Leader résolu à la soumission, voir
            // submit()) — seul ce Team Leader-là décide, jamais un autre Team Leader ni "TEAM_LEADER"
            // comme équipe générique passée par le contrôleur.
            User assignedTeamLeader = entity.getAssignedTo();
            if (assignedTeamLeader == null || !assignedTeamLeader.getUsername().equalsIgnoreCase(deciderUsername)) {
                throw ApiException.forbidden("This request is assigned to a specific Team Leader.");
            }
        } else if (!entity.getAssignedTeam().equals(deciderTeam)) {
            throw ApiException.forbidden("This request is assigned to the " + entity.getAssignedTeam() + " team.");
        }
        if (!STATUS_PENDING.equals(entity.getStatus())) {
            throw ApiException.badRequest("This request has already been decided (" + entity.getStatus() + ").");
        }

        User decider = findUser(deciderUsername);

        entity.setStatus(approve ? STATUS_APPROVED : STATUS_REJECTED);
        entity.setDecidedBy(decider);
        entity.setDecisionComment(comment);
        entity.setDecidedAt(LocalDateTime.now());

        return toResponse(workflowRequestRepository.save(entity));
    }

    /** Le demandeur peut retirer sa propre demande (peu importe le statut) ; QA/Admin peut
     *  supprimer n'importe quelle demande visible dans l'Historique (déjà réservé à QA/Admin
     *  — inutile d'exiger en plus que l'équipe assignée corresponde mot pour mot : ça
     *  bloquerait la suppression de toute demande liée à une équipe du référentiel RCC360,
     *  ex. MONETIQUE, puisque reviewerTeam ne vaut jamais que "QA" ou "ADMIN"). */
    @Transactional
    public void delete(Integer requestId, String username, String reviewerTeam) {
        WorkflowRequest entity = workflowRequestRepository.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Unknown workflow request."));

        boolean isOwner = entity.getRequestedBy() != null && username.equalsIgnoreCase(entity.getRequestedBy().getUsername());
        boolean isReviewer = reviewerTeam != null; // déjà QA ou ADMIN, voir WorkflowController.requireReviewerTeam()
        if (!isOwner && !isReviewer) {
            throw ApiException.forbidden("You can only delete your own request.");
        }
        workflowRequestRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public com.ecobank.rccportal.dto.SlaOverviewResponse getSlaOverview(boolean includeOverdueDetails) {
        int threshold = getSlaThresholdHours();
        List<WorkflowRequest> all = workflowRequestRepository.findAll();

        // Moyenne du temps de décision (heures) par équipe + type, sur les demandes déjà tranchées.
        java.util.Map<String, java.util.List<WorkflowRequest>> grouped = all.stream()
                .filter(r -> r.getDecidedAt() != null)
                .collect(java.util.stream.Collectors.groupingBy(r -> r.getAssignedTeam() + "|" + r.getType()));

        List<com.ecobank.rccportal.dto.SlaTeamStatsResponse> stats = new java.util.ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            List<WorkflowRequest> list = entry.getValue();
            int groupThreshold = resolveThresholdFor(parts[0], parts[1]);
            double avgHours = list.stream()
                    .mapToLong(r -> java.time.Duration.between(r.getCreatedAt(), r.getDecidedAt()).toHours())
                    .average().orElse(0);
            long overdue = all.stream()
                    .filter(r -> parts[0].equals(r.getAssignedTeam()) && parts[1].equals(r.getType()))
                    .filter(r -> STATUS_PENDING.equals(r.getStatus()))
                    .filter(r -> java.time.Duration.between(r.getCreatedAt(), LocalDateTime.now()).toHours() >= groupThreshold)
                    .count();
            stats.add(new com.ecobank.rccportal.dto.SlaTeamStatsResponse(parts[0], parts[1], list.size(), Math.round(avgHours * 10) / 10.0, overdue, groupThreshold));
        }
        // Complète avec les combinaisons équipe+type qui ont un seuil SLA configuré (référentiel
        // RCC360 seedé au démarrage, ou règle ajoutée manuellement par QA/Admin) mais aucune
        // demande encore tranchée — pour que l'équipe/le motif apparaisse tout de suite dans le
        // tableau (0 traitée, seuil visible) plutôt que d'attendre la première décision.
        try {
            for (com.ecobank.rccportal.model.SlaTarget target : slaTargetRepository.findAllByOrderByTeamAscTypeAsc()) {
                boolean alreadyPresent = grouped.containsKey(target.getTeam() + "|" + target.getType());
                if (alreadyPresent) continue;
                long overdue = all.stream()
                        .filter(r -> target.getTeam().equals(r.getAssignedTeam()) && target.getType().equals(r.getType()))
                        .filter(r -> STATUS_PENDING.equals(r.getStatus()))
                        .filter(r -> java.time.Duration.between(r.getCreatedAt(), LocalDateTime.now()).toHours() >= target.getThresholdHours())
                        .count();
                stats.add(new com.ecobank.rccportal.dto.SlaTeamStatsResponse(target.getTeam(), target.getType(), 0, 0, overdue, target.getThresholdHours()));
            }
        } catch (Exception e) {
            // Table SlaTargets absente (migration pas encore exécutée) — pas grave, on garde juste les stats réelles.
        }

        stats.sort(java.util.Comparator.comparing(com.ecobank.rccportal.dto.SlaTeamStatsResponse::team)
                .thenComparing(com.ecobank.rccportal.dto.SlaTeamStatsResponse::type));

        List<WorkflowRequestResponse> overdueRequests = !includeOverdueDetails ? List.of() : all.stream()
                .filter(r -> STATUS_PENDING.equals(r.getStatus()))
                .filter(r -> java.time.Duration.between(r.getCreatedAt(), LocalDateTime.now()).toHours() >= resolveThresholdFor(r.getAssignedTeam(), r.getType()))
                .sorted(java.util.Comparator.comparing(WorkflowRequest::getCreatedAt))
                .map(this::toResponse)
                .toList();

        return new com.ecobank.rccportal.dto.SlaOverviewResponse(threshold, stats, overdueRequests);
    }

    private User findUser(String username) {
        return userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
    }

    /** Team Leader dont User.ledTeam correspond à l'équipe donnée (comparaison insensible à la
     *  casse — TeamClassifier code, ex. "INBOUND_VOICE") — null si aucun n'est configuré. Un rôle
     *  TEAM_LEADER existe déjà comme rôle applicatif normal (voir UserRoleRepository) ; ledTeam,
     *  lui, n'est renseigné que pour ces comptes-là (voir User.ledTeam). */
    private User findTeamLeaderForTeam(String team) {
        return userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER").stream()
                .map(com.ecobank.rccportal.model.UserRole::getUser)
                .filter(u -> u.getLedTeam() != null && u.getLedTeam().equalsIgnoreCase(team))
                .findFirst()
                .orElse(null);
    }

    /** Alerte tous les comptes ADMIN — un agent bloqué de soumettre son congé faute de Team
     *  Leader affecté à son équipe est un trou d'organigramme à corriger côté admin, pas quelque
     *  chose que l'agent peut résoudre lui-même. */
    private void notifyAdminsOfMissingTeamLeader(User requester, String team) {
        String requesterLabel = requester.getName() != null ? requester.getName() : requester.getUsername();
        String content = "Aucun Team Leader configuré pour l'équipe \"" + team + "\" — "
                + requesterLabel + " n'a pas pu soumettre sa demande de congé. Affectez un Team Leader à cette équipe (ledTeam).";
        userRoleRepository.findByRoleNameIgnoreCase("ADMIN").stream()
                .map(com.ecobank.rccportal.model.UserRole::getUser)
                .distinct()
                .forEach(admin -> notificationRepository.save(RccNotification.builder()
                        .targetUser(admin)
                        .content(content)
                        .isRead(false)
                        .build()));
    }

    private WorkflowRequestResponse toResponse(WorkflowRequest e) {
        User requestedBy = safeUser(e.getRequestedBy());
        User assignedTo = safeUser(e.getAssignedTo());
        User decidedBy = safeUser(e.getDecidedBy());

        LocalDateTime openUntil = "PENDING".equals(e.getStatus()) ? LocalDateTime.now() : e.getDecidedAt();
        Long hoursOpen = (e.getCreatedAt() != null && openUntil != null)
                ? java.time.Duration.between(e.getCreatedAt(), openUntil).toHours()
                : null;
        Boolean slaBreached = "PENDING".equals(e.getStatus()) && hoursOpen != null
                && hoursOpen >= resolveThresholdFor(e.getAssignedTeam(), e.getType());

        return new WorkflowRequestResponse(
                e.getRequestId(),
                e.getType(),
                e.getTitle(),
                e.getDetails(),
                e.getPeriodType(),
                e.getPeriodFrom(),
                e.getPeriodTo(),
                e.getAssignedTeam(),
                assignedTo != null ? assignedTo.getUsername() : null,
                assignedTo != null ? assignedTo.getName() : null,
                e.getStatus(),
                requestedBy != null ? requestedBy.getUsername() : null,
                requestedBy != null ? requestedBy.getName() : null,
                requestedBy != null ? requestedBy.getAffiliateBranch() : null,
                getPrimaryServiceName(requestedBy),
                requestedBy != null ? requestedBy.getActivity() : null,
                safeServiceCode(e.getRelatedService()),
                safeServiceName(e.getRelatedService()),
                e.getCreatedAt(),
                decidedBy != null ? decidedBy.getUsername() : null,
                e.getDecisionComment(),
                e.getDecidedAt(),
                hoursOpen,
                slaBreached
        );
    }

    /**
     * Un simple "!= null" ne détecte pas une ligne supprimée en base (le proxy Hibernate
     * existe encore, seul l'accès à un champ échoue) — renvoie null dans ce cas plutôt que
     * de faire planter toute la liste à cause d'une seule demande liée à un compte disparu.
     */
    private User safeUser(User user) {
        if (user == null) return null;
        try {
            user.getUsername(); // force l'initialisation du proxy, capture l'échec ici
            return user;
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    private String safeServiceCode(com.ecobank.rccportal.model.RccService service) {
        if (service == null) return null;
        try {
            return service.getCode();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    private String safeServiceName(com.ecobank.rccportal.model.RccService service) {
        if (service == null) return null;
        try {
            return service.getName();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    private String getPrimaryServiceName(User user) {
        if (user == null || user.getId() == null) return null;
        var assignments = userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (assignments == null || assignments.isEmpty()) return null;
        try {
            var service = assignments.get(0).getService();
            return service != null ? service.getName() : null;
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null; // service lié supprimé depuis — n'empêche pas l'affichage de la demande
        }
    }
}
