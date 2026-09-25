package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.QualityScoreCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Grilles d'évaluation d'appel (Clairaudio) — mêmes règles que le module
 * d'origine : notation 0/1/2 par critère, critères éliminatoires (KO), seuil
 * de réussite pondéré (voir QualityScoreCalculator).
 */
@Service
public class QualityEvaluationService {

    private static final List<String> VALID_FEEDBACK_STATUSES = List.of("pending", "done");

    private final QualityEvaluationRepository evaluationRepository;
    private final QualityEvaluationScoreRepository scoreRepository;
    private final QualityCriterionRepository criterionRepository;
    private final QualityCriterionAttributeRepository criterionAttributeRepository;
    private final QualityMotifRepository motifRepository;
    private final UserRepository userRepository;
    private final com.ecobank.rccportal.repository.RccNotificationRepository rccNotificationRepository;
    private final com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository;

    public QualityEvaluationService(QualityEvaluationRepository evaluationRepository,
                                     QualityEvaluationScoreRepository scoreRepository,
                                     QualityCriterionRepository criterionRepository,
                                     QualityCriterionAttributeRepository criterionAttributeRepository,
                                     QualityMotifRepository motifRepository,
                                     UserRepository userRepository,
                                     com.ecobank.rccportal.repository.RccNotificationRepository rccNotificationRepository,
                                     com.ecobank.rccportal.repository.UserRoleRepository userRoleRepository) {
        this.evaluationRepository = evaluationRepository;
        this.scoreRepository = scoreRepository;
        this.criterionRepository = criterionRepository;
        this.criterionAttributeRepository = criterionAttributeRepository;
        this.motifRepository = motifRepository;
        this.userRepository = userRepository;
        this.rccNotificationRepository = rccNotificationRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public List<QualityCriterionResponse> listCriteria() {
        List<QualityCriterion> criteria = criterionRepository.findAllByOrderBySortOrderAsc();
        if (criteria.isEmpty()) return List.of();
        // Une seule requête pour les attributs de tous les critères (évite le N+1 par critère).
        Map<Integer, List<String>> attributeLabelsByCriterionId = criterionAttributeRepository
                .findByCriterionInOrderBySortOrderAsc(criteria).stream()
                .collect(Collectors.groupingBy(a -> a.getCriterion().getCriterionId(),
                        Collectors.mapping(QualityCriterionAttribute::getLabel, Collectors.toList())));

        return criteria.stream()
                .map(c -> new QualityCriterionResponse(
                        c.getCriterionId(), c.getCode(), c.getSection(), c.getName(), c.getDescription(),
                        c.getWeight(), Boolean.TRUE.equals(c.getIsKnockOut()), c.getSortOrder(),
                        attributeLabelsByCriterionId.getOrDefault(c.getCriterionId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QualityMotifResponse> listMotifs() {
        return motifRepository.findAll().stream()
                .map(m -> new QualityMotifResponse(m.getMotifId(), m.getLabel())).toList();
    }

    @Transactional(readOnly = true)
    public List<QualityEvaluationResponse> listAll() {
        return toResponses(evaluationRepository.findAllByOrderByEvaluationDateDesc());
    }

    @Transactional(readOnly = true)
    public List<QualityEvaluationResponse> listForAgent(String username) {
        User agent = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        return toResponses(evaluationRepository.findByAgentOrderByEvaluationDateDesc(agent));
    }

    @Transactional(readOnly = true)
    public QualityEvaluationResponse getById(Integer id) {
        return toResponse(evaluationRepository.findById(id).orElseThrow(() -> ApiException.notFound("Evaluation not found.")));
    }

    private void assertValid(QualityEvaluationRequest request) {
        if (request. agentMatricule() == null || request. agentMatricule().isBlank()) {
            throw ApiException.badRequest(" agentMatricule is required.");
        }
        if (request.evaluationDate() == null) throw ApiException.badRequest("evaluationDate is required.");
        if (request.callDate() == null) throw ApiException.badRequest("callDate is required.");
        if (request.feedbackStatus() != null && !VALID_FEEDBACK_STATUSES.contains(request.feedbackStatus())) {
            throw ApiException.badRequest("feedbackStatus must be one of: " + VALID_FEEDBACK_STATUSES);
        }
    }

    @Transactional
    public QualityEvaluationResponse create(QualityEvaluationRequest request, AuthenticatedUser evaluatorPrincipal) {
        assertValid(request);
        User agent = userRepository.findFirstByUsernameIgnoreCase(request. agentMatricule())
                .orElseThrow(() -> ApiException.badRequest("Unknown agent."));
        User evaluator = userRepository.findFirstByUsernameIgnoreCase(evaluatorPrincipal.username()).orElse(null);
        QualityMotif motif = request.motifId() != null
                ? motifRepository.findById(request.motifId()).orElseThrow(() -> ApiException.badRequest("Unknown motif."))
                : null;

        QualityEvaluation evaluation = evaluationRepository.save(QualityEvaluation.builder()
                .agent(agent).evaluator(evaluator)
                .evaluationDate(request.evaluationDate()).callDate(request.callDate())
                .recordingRef(request.recordingRef()).durationMinutes(request.durationMinutes())
                .motif(motif).strengths(request.strengths()).improvements(request.improvements())
                .comment(request.comment())
                .feedbackStatus(request.feedbackStatus() != null ? request.feedbackStatus() : "pending")
                .feedbackDate(request.feedbackDate())
                .build());

        saveScores(evaluation, request.scores());
        notifyTeamLeaderOfEvaluation(agent, evaluator);
        return toResponse(evaluation);
    }

    /** Prévient le Team Leader de l'équipe de l'agent qu'une écoute vient d'être faite —
     *  actionType="QA_EVALUATION_REVIEW", actionTarget=username de l'agent évalué (voir
     *  notifications.js / session.js pour le traitement côté frontend, en modale). */
    private void notifyTeamLeaderOfEvaluation(User agent, User evaluator) {
        var team = com.ecobank.rccportal.util.TeamClassifier.classify(agent.getActivity());
        if (team == com.ecobank.rccportal.util.TeamClassifier.Team.OTHER) return;

        User teamLeader = userRepository.findAll().stream()
                .filter(u -> team.name().equalsIgnoreCase(u.getLedTeam()) && hasRole(u, "team_leader"))
                .findFirst().orElse(null);
        if (teamLeader == null) return;

        String evaluatorLabel = evaluator != null ? (evaluator.getName() != null ? evaluator.getName() : evaluator.getUsername()) : "QA";
        String agentLabel = agent.getName() != null ? agent.getName() : agent.getUsername();
        rccNotificationRepository.save(com.ecobank.rccportal.model.RccNotification.builder()
                .targetUser(teamLeader)
                .content("Votre agent " + agentLabel + " a été écouté par " + evaluatorLabel + " — veuillez consulter le résultat.")
                .isRead(false)
                .actionType("QA_EVALUATION_REVIEW")
                .actionTarget(agent.getUsername())
                .build());
    }

    /** true si l'utilisateur porte ce rôle (comparaison insensible à la casse) — User n'a pas
     *  de champ "role" direct, il passe toujours par UserRoles. */
    private boolean hasRole(User user, String roleName) {
        if (user == null || user.getId() == null) return false;
        return userRoleRepository.findRolesByUserId(user.getId()).stream()
                .anyMatch(ur -> ur.getRole() != null && roleName.equalsIgnoreCase(ur.getRole().getName()));
    }

    /** Seuls l'évaluateur d'origine ou un admin peuvent modifier/supprimer une grille. */
    @Transactional
    public QualityEvaluationResponse update(Integer id, QualityEvaluationRequest request, AuthenticatedUser requester) {
        QualityEvaluation evaluation = evaluationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Evaluation not found."));
        assertEvaluatorOrAdmin(evaluation, requester);

        if (request.evaluationDate() != null) evaluation.setEvaluationDate(request.evaluationDate());
        if (request.callDate() != null) evaluation.setCallDate(request.callDate());
        if (request.recordingRef() != null) evaluation.setRecordingRef(request.recordingRef());
        if (request.durationMinutes() != null) evaluation.setDurationMinutes(request.durationMinutes());
        if (request.motifId() != null) {
            evaluation.setMotif(motifRepository.findById(request.motifId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown motif.")));
        }
        if (request.strengths() != null) evaluation.setStrengths(request.strengths());
        if (request.improvements() != null) evaluation.setImprovements(request.improvements());
        if (request.comment() != null) evaluation.setComment(request.comment());
        if (request.feedbackStatus() != null) {
            if (!VALID_FEEDBACK_STATUSES.contains(request.feedbackStatus())) {
                throw ApiException.badRequest("feedbackStatus must be one of: " + VALID_FEEDBACK_STATUSES);
            }
            evaluation.setFeedbackStatus(request.feedbackStatus());
        }
        if (request.feedbackDate() != null) evaluation.setFeedbackDate(request.feedbackDate());
        evaluationRepository.save(evaluation);

        if (request.scores() != null) {
            scoreRepository.deleteByEvaluation(evaluation);
            saveScores(evaluation, request.scores());
        }

        return toResponse(evaluation);
    }

    @Transactional
    public void remove(Integer id, AuthenticatedUser requester) {
        QualityEvaluation evaluation = evaluationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Evaluation not found."));
        assertEvaluatorOrAdmin(evaluation, requester);
        evaluationRepository.delete(evaluation);
    }

    private void assertEvaluatorOrAdmin(QualityEvaluation evaluation, AuthenticatedUser requester) {
        if ("admin".equalsIgnoreCase(requester.role())) return;
        String evaluatorusername = evaluation.getEvaluator() != null ? evaluation.getEvaluator().getUsername() : null;
        if (!requester.username().equals(evaluatorusername)) {
            throw ApiException.forbidden("You can only modify evaluations you created.");
        }
    }

    private void saveScores(QualityEvaluation evaluation, List<ScoreEntryDto> scores) {
        if (scores == null) return;
        for (ScoreEntryDto entry : scores) {
            QualityCriterion criterion = criterionRepository.findById(entry.criterionId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown criterion: " + entry.criterionId()));
            scoreRepository.save(QualityEvaluationScore.builder()
                    .evaluation(evaluation).criterion(criterion)
                    .scoreValue(entry.isNotApplicable() ? null : entry.scoreValue())
                    .isNotApplicable(entry.isNotApplicable())
                    .build());
        }
    }

    /** Charge les scores de toutes les évaluations en une seule requête (évite le N+1 par évaluation). */
    private List<QualityEvaluationResponse> toResponses(List<QualityEvaluation> evaluations) {
        if (evaluations.isEmpty()) return List.of();
        Map<Integer, List<QualityEvaluationScore>> scoresByEvaluationId = scoreRepository
                .findByEvaluationIn(evaluations).stream()
                .collect(Collectors.groupingBy(s -> s.getEvaluation().getEvaluationId()));
        return evaluations.stream()
                .map(e -> toResponse(e, scoresByEvaluationId.getOrDefault(e.getEvaluationId(), List.of())))
                .toList();
    }

    private QualityEvaluationResponse toResponse(QualityEvaluation e) {
        return toResponse(e, scoreRepository.findByEvaluation(e));
    }

    private QualityEvaluationResponse toResponse(QualityEvaluation e, List<QualityEvaluationScore> scores) {
        QualityScoreCalculator.Result result = QualityScoreCalculator.compute(scores);

        List<ScoreEntryDto> scoreDtos = scores.stream()
                .map(s -> new ScoreEntryDto(s.getCriterion().getCriterionId(), s.getScoreValue(),
                        Boolean.TRUE.equals(s.getIsNotApplicable())))
                .toList();

        return new QualityEvaluationResponse(
                e.getEvaluationId(),
                e.getAgent().getUsername(), e.getAgent().getName(),
                e.getEvaluator() != null ? e.getEvaluator().getUsername() : null,
                e.getEvaluationDate(), e.getCallDate(),
                e.getRecordingRef(), e.getDurationMinutes(),
                e.getMotif() != null ? e.getMotif().getMotifId() : null,
                e.getMotif() != null ? e.getMotif().getLabel() : null,
                e.getStrengths(), e.getImprovements(), e.getComment(),
                e.getFeedbackStatus(), e.getFeedbackDate(),
                scoreDtos,
                result.scorePercentage(), result.passed(), result.knockedOut(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getChannel());
    }
}
