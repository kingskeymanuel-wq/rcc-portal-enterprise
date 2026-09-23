package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.ShiftSwapResponse;
import com.ecobank.rccportal.model.AgentSchedule;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.ShiftSwapRequest;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserRole;
import com.ecobank.rccportal.repository.AgentScheduleRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.repository.ShiftSwapRequestRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.repository.UserRoleRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Permutation de shift entre deux agents de la même équipe — workflow à 2 étapes, voir la
 * javadoc de ShiftSwapRequest pour la séquence complète. Point essentiel : l'échange RÉEL des
 * deux AgentSchedule (shiftCode/heures) n'a lieu qu'à l'approbation du Team Leader — jamais
 * avant, même après acceptation du pair (qui n'est qu'une étape intermédiaire, pas un accord
 * final).
 */
@Service
public class ShiftSwapService {

    private final ShiftSwapRequestRepository shiftSwapRequestRepository;
    private final AgentScheduleRepository agentScheduleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RccNotificationRepository notificationRepository;

    public ShiftSwapService(ShiftSwapRequestRepository shiftSwapRequestRepository,
                             AgentScheduleRepository agentScheduleRepository,
                             UserRepository userRepository,
                             UserRoleRepository userRoleRepository,
                             RccNotificationRepository notificationRepository) {
        this.shiftSwapRequestRepository = shiftSwapRequestRepository;
        this.agentScheduleRepository = agentScheduleRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.notificationRepository = notificationRepository;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Étape 1 — l'agent demandeur propose l'échange à un pair de son équipe
    // ═══════════════════════════════════════════════════════════════════

    /** Rôles qui ne sont PAS des agents : ils encadrent ou planifient, ils n'échangent pas de shift. */
    private static final java.util.Set<String> NON_AGENT_ROLES =
            java.util.Set.of("TEAM_LEADER", "SUPERVISOR", "RH", "ADMIN", "EXCELLIAM");

    /** Compatibilité — sans rôle connu, seul le contrôle sur les rôles en base s'applique. */
    @Transactional
    public ShiftSwapResponse submit(String requesterUsername, LocalDate requesterDate, String targetUsername,
                                     LocalDate targetDate, String message) {
        return submit(requesterUsername, null, requesterDate, targetUsername, targetDate, message);
    }

    /**
     * La permutation de shift concerne UNIQUEMENT les agents : ni le demandeur ni l'agent visé
     * ne peuvent être Team Leader, Superviseur, RH, Admin ou Excelliam (le Team Leader, lui,
     * valide ou refuse — voir decideByTeamLeader).
     */
    @Transactional
    public ShiftSwapResponse submit(String requesterUsername, String requesterRole, LocalDate requesterDate,
                                     String targetUsername, LocalDate targetDate, String message) {
        if (requesterRole != null && NON_AGENT_ROLES.contains(requesterRole.toUpperCase(java.util.Locale.ROOT))) {
            throw ApiException.forbidden("La permutation de shift est réservée aux agents.");
        }
        if (requesterDate == null || targetDate == null) {
            throw ApiException.badRequest("Les deux dates sont requises.");
        }
        if (targetUsername == null || targetUsername.isBlank()) {
            throw ApiException.badRequest("L'agent visé est requis.");
        }

        User requester = findUser(requesterUsername);
        User target = userRepository.findFirstByUsernameIgnoreCase(targetUsername.trim())
                .orElseThrow(() -> ApiException.badRequest("Agent inconnu : " + targetUsername));

        if (!isAgent(requester)) {
            throw ApiException.forbidden("La permutation de shift est réservée aux agents.");
        }
        if (!isAgent(target)) {
            throw ApiException.badRequest("La permutation n'est possible qu'avec un agent (pas un Team Leader, superviseur, RH ou administrateur).");
        }

        if (requester.getId().equals(target.getId())) {
            throw ApiException.badRequest("Vous ne pouvez pas vous faire une demande à vous-même.");
        }

        String requesterTeam = requester.getActivity();
        if (requesterTeam == null || requesterTeam.isBlank() || !requesterTeam.equalsIgnoreCase(target.getActivity())) {
            throw ApiException.badRequest("La permutation n'est possible qu'avec un agent de votre propre équipe.");
        }

        // Les deux jours doivent porter un shift réel (APPROVED, avec une heure de début) —
        // pas de sens à "échanger" un jour OFF/congé ou un planning pas encore validé par le TL.
        AgentSchedule requesterSchedule = requireWorkedApprovedSchedule(requester, requesterDate, "votre");
        AgentSchedule targetSchedule = requireWorkedApprovedSchedule(target, targetDate, "l'agent visé");

        ShiftSwapRequest entity = ShiftSwapRequest.builder()
                .requester(requester)
                .requesterDate(requesterDate)
                .targetUser(target)
                .targetDate(targetDate)
                .requesterMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message)
                .peerStatus("PENDING")
                .teamLeaderStatus("NOT_SUBMITTED")
                .build();
        entity = shiftSwapRequestRepository.save(entity);

        notificationRepository.save(RccNotification.builder()
                .targetUser(target)
                .content((requester.getName() != null ? requester.getName() : requester.getUsername())
                        + " vous propose d'échanger son shift du " + requesterDate + " (" + requesterSchedule.getShiftCode()
                        + ") contre votre shift du " + targetDate + " (" + targetSchedule.getShiftCode() + ").")
                .isRead(false)
                .build());

        return toResponse(entity);
    }

    private AgentSchedule requireWorkedApprovedSchedule(User user, LocalDate date, String possessive) {
        AgentSchedule schedule = agentScheduleRepository.findByUserAndWorkDate(user, date)
                .orElseThrow(() -> ApiException.badRequest("Aucun shift trouvé pour " + possessive + " journée du " + date + "."));
        if (!"APPROVED".equals(schedule.getApprovalStatus())) {
            throw ApiException.badRequest("Le planning de " + possessive + " journée du " + date + " n'est pas encore validé — impossible de l'échanger.");
        }
        if (schedule.getPlannedStartTime() == null) {
            throw ApiException.badRequest("Impossible d'échanger " + possessive + " journée du " + date + " : ce n'est pas un jour travaillé (OFF/congé/absence).");
        }
        return schedule;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Étape 2 — l'agent visé accepte ou refuse
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public ShiftSwapResponse decideByPeer(Integer swapRequestId, String peerUsername, boolean approve, String comment) {
        // Remarque : comment n'est pas persisté à cette étape (pas de colonne dédiée côté pair,
        // volontairement — la demande métier ne prévoit un motif obligatoire que pour le refus
        // du Team Leader). Un refus du pair reste visible pour le demandeur via la notification
        // envoyée plus bas, sans motif structuré.
        ShiftSwapRequest entity = findSwap(swapRequestId);
        if (!entity.getTargetUser().getUsername().equalsIgnoreCase(peerUsername)) {
            throw ApiException.forbidden("Cette demande ne vous est pas adressée.");
        }
        if (!"PENDING".equals(entity.getPeerStatus())) {
            throw ApiException.badRequest("Cette demande a déjà été traitée (" + entity.getPeerStatus() + ").");
        }

        entity.setPeerStatus(approve ? "ACCEPTED" : "REJECTED");
        entity.setPeerDecidedAt(LocalDateTime.now());

        User requester = entity.getRequester();
        if (approve) {
            entity.setTeamLeaderStatus("PENDING");
            User teamLeader = findTeamLeaderForTeam(requester.getActivity());
            if (teamLeader != null) {
                notificationRepository.save(RccNotification.builder()
                        .targetUser(teamLeader)
                        .content("Permutation de shift à valider : " + labelFor(requester) + " (" + entity.getRequesterDate()
                                + ") ↔ " + labelFor(entity.getTargetUser()) + " (" + entity.getTargetDate() + ").")
                        .isRead(false)
                        .build());
            }
            notificationRepository.save(RccNotification.builder()
                    .targetUser(requester)
                    .content(labelFor(entity.getTargetUser()) + " a accepté votre demande de permutation — en attente de validation par le Team Leader.")
                    .isRead(false)
                    .build());
        } else {
            notificationRepository.save(RccNotification.builder()
                    .targetUser(requester)
                    .content(labelFor(entity.getTargetUser()) + " a refusé votre demande de permutation du " + entity.getRequesterDate() + ".")
                    .isRead(false)
                    .build());
        }

        return toResponse(shiftSwapRequestRepository.save(entity));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Étape 3 — le Team Leader valide ou refuse ; validation = échange réel
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public ShiftSwapResponse decideByTeamLeader(Integer swapRequestId, String teamLeaderUsername, boolean approve, String comment) {
        ShiftSwapRequest entity = findSwap(swapRequestId);
        User teamLeader = findUser(teamLeaderUsername);

        String requesterTeam = entity.getRequester().getActivity();
        if (teamLeader.getLedTeam() == null || !teamLeader.getLedTeam().equalsIgnoreCase(requesterTeam)) {
            throw ApiException.forbidden("Cette permutation concerne une équipe que vous ne dirigez pas.");
        }
        if (!"PENDING".equals(entity.getTeamLeaderStatus())) {
            throw ApiException.badRequest("Cette permutation a déjà été traitée par un Team Leader (" + entity.getTeamLeaderStatus() + ").");
        }

        entity.setTeamLeaderStatus(approve ? "APPROVED" : "REJECTED");
        entity.setDecidedByTeamLeader(teamLeader);
        entity.setTeamLeaderComment(comment);
        entity.setTeamLeaderDecidedAt(LocalDateTime.now());

        if (approve) {
            applySwap(entity);
        }
        entity = shiftSwapRequestRepository.save(entity);

        String resultText = approve
                ? "Votre permutation de shift a été VALIDÉE par le Team Leader — les plannings ont été échangés."
                : "Votre permutation de shift a été REFUSÉE par le Team Leader" + (comment != null && !comment.isBlank() ? " : " + comment : ".");
        notificationRepository.save(RccNotification.builder().targetUser(entity.getRequester()).content(resultText).isRead(false).build());
        notificationRepository.save(RccNotification.builder().targetUser(entity.getTargetUser()).content(resultText).isRead(false).build());

        return toResponse(entity);
    }

    /** Échange RÉEL des deux AgentSchedule — jamais avant cette étape (voir javadoc de la classe).
     *  swapApplied protège contre un double-échange si cette méthode était jamais rappelée. */
    private void applySwap(ShiftSwapRequest entity) {
        if (entity.isSwapApplied()) return;

        AgentSchedule requesterSchedule = agentScheduleRepository.findByUserAndWorkDate(entity.getRequester(), entity.getRequesterDate())
                .orElseThrow(() -> ApiException.badRequest("Le shift du demandeur n'existe plus — permutation annulée."));
        AgentSchedule targetSchedule = agentScheduleRepository.findByUserAndWorkDate(entity.getTargetUser(), entity.getTargetDate())
                .orElseThrow(() -> ApiException.badRequest("Le shift de l'agent visé n'existe plus — permutation annulée."));

        // Échange complet (code + heures + libellé + chevauchement minuit) — jamais seulement le
        // code, pour ne jamais désynchroniser shiftCode et les heures réellement affichées.
        String rCode = requesterSchedule.getShiftCode(), rLabel = requesterSchedule.getShiftLabel();
        var rStart = requesterSchedule.getPlannedStartTime(); var rEnd = requesterSchedule.getPlannedEndTime();
        boolean rOvernight = requesterSchedule.isOvernightCrossesMidnight();

        requesterSchedule.setShiftCode(targetSchedule.getShiftCode());
        requesterSchedule.setShiftLabel(targetSchedule.getShiftLabel());
        requesterSchedule.setPlannedStartTime(targetSchedule.getPlannedStartTime());
        requesterSchedule.setPlannedEndTime(targetSchedule.getPlannedEndTime());
        requesterSchedule.setOvernightCrossesMidnight(targetSchedule.isOvernightCrossesMidnight());

        targetSchedule.setShiftCode(rCode);
        targetSchedule.setShiftLabel(rLabel);
        targetSchedule.setPlannedStartTime(rStart);
        targetSchedule.setPlannedEndTime(rEnd);
        targetSchedule.setOvernightCrossesMidnight(rOvernight);

        agentScheduleRepository.save(requesterSchedule);
        agentScheduleRepository.save(targetSchedule);
        entity.setSwapApplied(true);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lecture
    // ═══════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<ShiftSwapResponse> listMine(String username) {
        User user = findUser(username);
        return shiftSwapRequestRepository.findByRequesterOrTargetUserOrderByCreatedAtDesc(user, user).stream()
                .map(this::toResponse).toList();
    }

    /** File du pair — demandes qui lui sont adressées, en attente de SA décision. */
    @Transactional(readOnly = true)
    public List<ShiftSwapResponse> listPendingForPeer(String username) {
        User user = findUser(username);
        return shiftSwapRequestRepository.findByTargetUserAndPeerStatusOrderByCreatedAtAsc(user, "PENDING").stream()
                .map(this::toResponse).toList();
    }

    /** File du Team Leader — acceptées par le pair, restreinte à SA propre équipe (ledTeam). */
    @Transactional(readOnly = true)
    public List<ShiftSwapResponse> listPendingForTeamLeader(String teamLeaderUsername) {
        User teamLeader = findUser(teamLeaderUsername);
        if (teamLeader.getLedTeam() == null || teamLeader.getLedTeam().isBlank()) return List.of();
        return shiftSwapRequestRepository.findByTeamLeaderStatusOrderByCreatedAtAsc("PENDING").stream()
                .filter(s -> teamLeader.getLedTeam().equalsIgnoreCase(s.getRequester().getActivity()))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Toutes les permutations de l'équipe du Team Leader (tous statuts, plus récentes d'abord) —
     * bouton « Permutations » du portail Team Leader : il voit l'ensemble (en attente du
     * collègue, à valider, validées, refusées) et décide celles qui l'attendent.
     */
    @Transactional(readOnly = true)
    public List<ShiftSwapResponse> listForTeamLeader(String teamLeaderUsername) {
        User teamLeader = findUser(teamLeaderUsername);
        if (teamLeader.getLedTeam() == null || teamLeader.getLedTeam().isBlank()) return List.of();
        return shiftSwapRequestRepository.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")).stream()
                .filter(s -> teamLeader.getLedTeam().equalsIgnoreCase(s.getRequester().getActivity()))
                .map(this::toResponse)
                .toList();
    }

    private boolean isAgent(User user) {
        if (user.getLedTeam() != null && !user.getLedTeam().isBlank()) return false; // dirige une équipe
        return userRoleRepository.findRolesByUserId(user.getId()).stream()
                .map(ur -> ur.getRole() != null ? ur.getRole().getName() : null)
                .filter(java.util.Objects::nonNull)
                .noneMatch(name -> NON_AGENT_ROLES.contains(name.toUpperCase(java.util.Locale.ROOT)));
    }

    private ShiftSwapRequest findSwap(Integer id) {
        return shiftSwapRequestRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Demande de permutation introuvable."));
    }

    private User findUser(String username) {
        return userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
    }

    private User findTeamLeaderForTeam(String team) {
        if (team == null || team.isBlank()) return null;
        return userRoleRepository.findByRoleNameIgnoreCase("TEAM_LEADER").stream()
                .map(UserRole::getUser)
                .filter(u -> u.getLedTeam() != null && u.getLedTeam().equalsIgnoreCase(team))
                .findFirst()
                .orElse(null);
    }

    private String labelFor(User u) {
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
    }

    private ShiftSwapResponse toResponse(ShiftSwapRequest e) {
        User requester = e.getRequester();
        User target = e.getTargetUser();
        AgentSchedule requesterSchedule = agentScheduleRepository.findByUserAndWorkDate(requester, e.getRequesterDate()).orElse(null);
        AgentSchedule targetSchedule = agentScheduleRepository.findByUserAndWorkDate(target, e.getTargetDate()).orElse(null);

        return new ShiftSwapResponse(
                e.getSwapRequestId(),
                requester.getUsername(), requester.getName(), e.getRequesterDate(),
                requesterSchedule != null ? requesterSchedule.getShiftCode() : null,
                requesterSchedule != null ? requesterSchedule.getShiftLabel() : null,
                target.getUsername(), target.getName(), e.getTargetDate(),
                targetSchedule != null ? targetSchedule.getShiftCode() : null,
                targetSchedule != null ? targetSchedule.getShiftLabel() : null,
                requester.getActivity(),
                e.getPeerStatus(),
                e.getTeamLeaderStatus(),
                e.getDecidedByTeamLeader() != null ? e.getDecidedByTeamLeader().getUsername() : null,
                e.getTeamLeaderComment(),
                e.getRequesterMessage(),
                e.getCreatedAt(),
                e.getPeerDecidedAt(),
                e.getTeamLeaderDecidedAt(),
                e.isSwapApplied()
        );
    }
}
