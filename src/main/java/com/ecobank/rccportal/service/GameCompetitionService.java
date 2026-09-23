package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CompetitionRequest;
import com.ecobank.rccportal.dto.CompetitionResponse;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Compétitions d'évaluation par équipe. Workflow :
 * 1. Le formateur (QA/Admin) crée une compétition sur un jeu, choisit les équipes en lice
 *    et une date programmée — ou coche "stagiaires uniquement".
 * 2. Si équipes classiques : chaque Team Leader concerné reçoit une notification, doit
 *    valider les membres de son équipe qui participeront (GameCompetitionTeam.validated).
 *    Si stagiaires uniquement : le formateur choisit directement les participants, aucune
 *    validation Team Leader nécessaire — l'équipe est marquée validée immédiatement.
 * 3. Une fois son équipe validée, un participant retenu voit la compétition dans son onglet
 *    Évaluation dès que scheduledAt est atteint — il entre, clique "Commencer", joue.
 * 4. Le résultat (classement) est visible par toute l'équipe concernée.
 */
@Service
public class GameCompetitionService {

    private final GameCompetitionRepository competitionRepository;
    private final GameCompetitionTeamRepository competitionTeamRepository;
    private final GameCompetitionParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final GameDefinitionRepository gameDefinitionRepository;
    private final RccNotificationRepository rccNotificationRepository;
    private final UserRoleRepository userRoleRepository;

    public GameCompetitionService(GameCompetitionRepository competitionRepository,
                                   GameCompetitionTeamRepository competitionTeamRepository,
                                   GameCompetitionParticipantRepository participantRepository,
                                   UserRepository userRepository,
                                   GameDefinitionRepository gameDefinitionRepository,
                                   RccNotificationRepository rccNotificationRepository,
                                   UserRoleRepository userRoleRepository) {
        this.competitionRepository = competitionRepository;
        this.competitionTeamRepository = competitionTeamRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.gameDefinitionRepository = gameDefinitionRepository;
        this.rccNotificationRepository = rccNotificationRepository;
        this.userRoleRepository = userRoleRepository;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CRÉATION (formateur / QA / Admin)
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public CompetitionResponse create(AuthenticatedUser requester, CompetitionRequest request) {
        requireTrainer(requester);
        if (request.gameKey() == null || request.gameKey().isBlank()) throw ApiException.badRequest("Le jeu est obligatoire.");
        if (request.title() == null || request.title().isBlank()) throw ApiException.badRequest("Le titre est obligatoire.");
        if (request.scheduledAt() == null) throw ApiException.badRequest("La date programmée est obligatoire.");
        if (!request.isTraineeOnly() && (request.teams() == null || request.teams().size() < 2)) {
            throw ApiException.badRequest("Choisissez au moins deux équipes en compétition (ou cochez « stagiaires uniquement »).");
        }

        GameCompetition competition = competitionRepository.save(GameCompetition.builder()
                .gameKey(request.gameKey())
                .title(request.title().trim())
                .scheduledAt(request.scheduledAt())
                .status(request.isTraineeOnly() ? "SCHEDULED" : "PENDING_VALIDATION")
                .isTraineeOnly(request.isTraineeOnly())
                .createdByUsername(requester.username())
                .build());

        if (request.isTraineeOnly()) {
            // Pas d'équipe classique — une seule "équipe" virtuelle STAGIAIRES, déjà validée
            // (le formateur choisit lui-même les participants, voir addTraineeParticipant()).
            competitionTeamRepository.save(GameCompetitionTeam.builder()
                    .competitionId(competition.getCompetitionId())
                    .team("STAGIAIRES")
                    .validated(true)
                    .validatedAt(LocalDateTime.now())
                    .validatedByUsername(requester.username())
                    .build());
        } else {
            for (String teamCode : request.teams()) {
                competitionTeamRepository.save(GameCompetitionTeam.builder()
                        .competitionId(competition.getCompetitionId())
                        .team(teamCode)
                        .validated(false)
                        .build());
                notifyTeamLeader(teamCode, competition);
            }
        }

        return toResponse(competition);
    }

    private void notifyTeamLeader(String teamCode, GameCompetition competition) {
        User teamLeader = userRepository.findAll().stream()
                .filter(u -> teamCode.equalsIgnoreCase(u.getLedTeam()) && hasRole(u, "team_leader"))
                .findFirst().orElse(null);
        if (teamLeader == null) return;

        rccNotificationRepository.save(RccNotification.builder()
                .targetUser(teamLeader)
                .content("Votre équipe est engagée dans la compétition « " + competition.getTitle() +
                        "» — veuillez choisir les membres qui participeront.")
                .isRead(false)
                .actionType("COMPETITION_TEAM_SELECTION")
                .actionTarget(String.valueOf(competition.getCompetitionId()))
                .build());
    }

    /** true si l'utilisateur porte ce rôle (comparaison insensible à la casse) — User n'a pas
     *  de champ "role" direct, il passe toujours par UserRoles. */
    private boolean hasRole(User user, String roleName) {
        if (user == null || user.getId() == null) return false;
        return userRoleRepository.findRolesByUserId(user.getId()).stream()
                .anyMatch(ur -> ur.getRole() != null && roleName.equalsIgnoreCase(ur.getRole().getName()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // VALIDATION DES MEMBRES (Team Leader)
    // ═══════════════════════════════════════════════════════════════════

    /** Le Team Leader choisit les membres de sa propre équipe pour cette compétition, puis valide. */
    @Transactional
    public CompetitionResponse validateTeamMembers(AuthenticatedUser requester, Integer competitionId, List<Long> userIds) {
        GameCompetition competition = competitionRepository.findById(competitionId)
                .orElseThrow(() -> ApiException.notFound("Compétition introuvable."));
        User leader = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
        if (!"team_leader".equalsIgnoreCase(requester.role()) || leader.getLedTeam() == null) {
            throw ApiException.forbidden("Réservé au Team Leader de l'équipe concernée.");
        }
        GameCompetitionTeam team = competitionTeamRepository.findByCompetitionIdAndTeam(competitionId, leader.getLedTeam())
                .orElseThrow(() -> ApiException.forbidden("Votre équipe n'est pas engagée dans cette compétition."));

        for (Long userId : userIds) {
            User participant = userRepository.findById(userId).orElse(null);
            if (participant == null) continue;
            if (!leader.getLedTeam().equalsIgnoreCase(TeamClassifier.classify(participant.getActivity()).name())) continue; // seulement sa propre équipe
            if (participantRepository.findByCompetitionIdAndUserId(competitionId, userId).isPresent()) continue;
            participantRepository.save(GameCompetitionParticipant.builder()
                    .competitionId(competitionId)
                    .userId(userId)
                    .team(leader.getLedTeam())
                    .build());
        }

        team.setValidated(true);
        team.setValidatedAt(LocalDateTime.now());
        team.setValidatedByUsername(requester.username());
        competitionTeamRepository.save(team);

        maybeAdvanceToScheduled(competition);
        return toResponse(competition);
    }

    /** Passe la compétition à SCHEDULED dès que toutes les équipes engagées ont validé. */
    private void maybeAdvanceToScheduled(GameCompetition competition) {
        boolean allValidated = competitionTeamRepository.findByCompetitionId(competition.getCompetitionId())
                .stream().allMatch(GameCompetitionTeam::getValidated);
        if (allValidated && "PENDING_VALIDATION".equals(competition.getStatus())) {
            competition.setStatus("SCHEDULED");
            competitionRepository.save(competition);
        }
    }

    /** Le formateur choisit directement les participants stagiaires (pas de Team Leader). */
    @Transactional
    public CompetitionResponse addTraineeParticipants(AuthenticatedUser requester, Integer competitionId, List<Long> userIds) {
        requireTrainer(requester);
        GameCompetition competition = competitionRepository.findById(competitionId)
                .orElseThrow(() -> ApiException.notFound("Compétition introuvable."));
        if (!Boolean.TRUE.equals(competition.getIsTraineeOnly())) {
            throw ApiException.badRequest("Cette compétition n'est pas réservée aux stagiaires.");
        }
        for (Long userId : userIds) {
            if (participantRepository.findByCompetitionIdAndUserId(competitionId, userId).isPresent()) continue;
            participantRepository.save(GameCompetitionParticipant.builder()
                    .competitionId(competitionId)
                    .userId(userId)
                    .team("STAGIAIRES")
                    .build());
        }
        return toResponse(competition);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PARTICIPATION (agent)
    // ═══════════════════════════════════════════════════════════════════

    /** Compétitions actives (équipe validée, date atteinte, agent retenu) dans lesquelles cet agent peut jouer. */
    @Transactional(readOnly = true)
    public List<CompetitionResponse> myActiveCompetitions(Long userId) {
        return participantRepository.findByUserId(userId).stream()
                .map(p -> competitionRepository.findById(p.getCompetitionId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(c -> !c.getScheduledAt().isAfter(LocalDateTime.now())) // date atteinte
                .filter(c -> !"COMPLETED".equals(c.getStatus()))
                .map(this::toResponse)
                .toList();
    }

    /** Enregistre le score d'un participant à la fin de sa partie. */
    @Transactional
    public void recordScore(Integer competitionId, Long userId, int score) {
        participantRepository.findByCompetitionIdAndUserId(competitionId, userId).ifPresent(p -> {
            p.setScore(score);
            p.setCompletedAt(LocalDateTime.now());
            participantRepository.save(p);
        });
        competitionRepository.findById(competitionId).ifPresent(c -> {
            c.setStatus("ACTIVE");
            competitionRepository.save(c);
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // LECTURE
    // ═══════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<CompetitionResponse> listAll(AuthenticatedUser requester) {
        requireTrainer(requester);
        return competitionRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    /** Compétitions où l'équipe du Team Leader connecté est engagée. */
    @Transactional(readOnly = true)
    public List<CompetitionResponse> listForTeamLeader(AuthenticatedUser requester) {
        User leader = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Utilisateur inconnu."));
        if (leader.getLedTeam() == null) return List.of();
        return competitionRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> competitionTeamRepository.findByCompetitionIdAndTeam(c.getCompetitionId(), leader.getLedTeam()).isPresent())
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CompetitionResponse getOne(Integer competitionId) {
        GameCompetition competition = competitionRepository.findById(competitionId)
                .orElseThrow(() -> ApiException.notFound("Compétition introuvable."));
        return toResponse(competition);
    }

    private void requireTrainer(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isQa) {
            throw ApiException.forbidden("Réservé au formateur (QA) ou à l'admin.");
        }
    }

    private CompetitionResponse toResponse(GameCompetition c) {
        String gameTitle = gameDefinitionRepository.findByGameKey(c.getGameKey()).map(g -> g.getTitle()).orElse(c.getGameKey());
        List<CompetitionResponse.CompetitionTeamResponse> teams = competitionTeamRepository.findByCompetitionId(c.getCompetitionId()).stream()
                .map(t -> {
                    var teamLabel = "STAGIAIRES".equals(t.getTeam()) ? "Stagiaires"
                            : TeamClassifier.Team.valueOf(t.getTeam()).label;
                    List<CompetitionResponse.CompetitionParticipantResponse> participants =
                            participantRepository.findByCompetitionIdAndTeam(c.getCompetitionId(), t.getTeam()).stream()
                                    .map(p -> {
                                        User u = userRepository.findById(p.getUserId()).orElse(null);
                                        return new CompetitionResponse.CompetitionParticipantResponse(
                                                p.getUserId(),
                                                u != null ? u.getUsername() : "—",
                                                u != null ? (u.getName() != null ? u.getName() : u.getUsername()) : "Agent supprimé",
                                                p.getScore(),
                                                p.getCompletedAt() != null);
                                    })
                                    .sorted((a, b) -> {
                                        int sa = a.score() != null ? a.score() : -1;
                                        int sb = b.score() != null ? b.score() : -1;
                                        return Integer.compare(sb, sa); // meilleur score en premier
                                    })
                                    .toList();
                    return new CompetitionResponse.CompetitionTeamResponse(t.getTeam(), teamLabel, t.getValidated(), t.getValidatedByUsername(), participants);
                })
                .toList();

        return new CompetitionResponse(c.getCompetitionId(), c.getGameKey(), gameTitle, c.getTitle(), c.getScheduledAt(),
                c.getStatus(), Boolean.TRUE.equals(c.getIsTraineeOnly()), c.getCreatedByUsername(), c.getCreatedAt(), teams);
    }
}
