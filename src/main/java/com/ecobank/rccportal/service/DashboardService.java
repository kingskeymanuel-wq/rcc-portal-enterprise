package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.DashboardActivityResponse;
import com.ecobank.rccportal.dto.DashboardResponse;
import com.ecobank.rccportal.model.LoginAudit;
import com.ecobank.rccportal.model.QualityEvaluation;
import com.ecobank.rccportal.model.QualityEvaluationScore;
import com.ecobank.rccportal.repository.LoginAuditRepository;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.QualityEvaluationRepository;
import com.ecobank.rccportal.repository.QualityEvaluationScoreRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.QualityScoreCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agrège les KPI du tableau de bord (GET /api/dashboard) — voir DashboardController.
 * Chaque chiffre vient d'une vraie requête sur une vraie table ; aucune valeur n'est
 * inventée. Cas particulier : "training" (Formation) n'a aucune table de suivi dans
 * le schéma actuel, donc il est renvoyé à null plutôt que d'afficher un faux chiffre —
 * voir DashboardResponse.
 */
@Service
public class DashboardService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Map<String, String> LOGIN_EVENT_LABELS = Map.of(
            "login_success", "Connexion",
            "login_failed", "Échec de connexion",
            "locked", "Compte verrouillé",
            "password_reset", "Réinitialisation mot de passe"
    );

    private final UserRepository userRepository;
    private final ProcedureRepository procedureRepository;
    private final QualityEvaluationRepository qualityEvaluationRepository;
    private final QualityEvaluationScoreRepository qualityEvaluationScoreRepository;
    private final LoginAuditRepository loginAuditRepository;

    public DashboardService(UserRepository userRepository,
                            ProcedureRepository procedureRepository,
                            QualityEvaluationRepository qualityEvaluationRepository,
                            QualityEvaluationScoreRepository qualityEvaluationScoreRepository,
                            LoginAuditRepository loginAuditRepository) {
        this.userRepository = userRepository;
        this.procedureRepository = procedureRepository;
        this.qualityEvaluationRepository = qualityEvaluationRepository;
        this.qualityEvaluationScoreRepository = qualityEvaluationScoreRepository;
        this.loginAuditRepository = loginAuditRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        long users = userRepository.count();
        long knowledgeBase = procedureRepository.count();
        Integer qa = computeConformityRate();
        List<DashboardActivityResponse> activities = recentActivities();

        // Pas de table de suivi "Formation" dans le schéma actuel — voir Javadoc de la classe.
        Integer training = null;

        return new DashboardResponse(users, knowledgeBase, training, qa, activities);
    }

    /**
     * Taux de conformité (% d'évaluations qualité réussies) — même règle que le module
     * Clairaudio (QualityScoreCalculator : seuil 80 %, critères éliminatoires inclus).
     * Retourne null s'il n'y a encore aucune évaluation (pas de "0 %" trompeur).
     */
    private Integer computeConformityRate() {
        List<QualityEvaluation> evaluations = qualityEvaluationRepository.findAllByOrderByEvaluationDateDesc();
        if (evaluations.isEmpty()) {
            return null;
        }

        List<QualityEvaluationScore> allScores = qualityEvaluationScoreRepository.findByEvaluationIn(evaluations);
        Map<Integer, List<QualityEvaluationScore>> scoresByEvaluationId = allScores.stream()
                .collect(Collectors.groupingBy(s -> s.getEvaluation().getEvaluationId()));

        long passedCount = evaluations.stream()
                .map(e -> scoresByEvaluationId.getOrDefault(e.getEvaluationId(), List.of()))
                .map(QualityScoreCalculator::compute)
                .filter(QualityScoreCalculator.Result::passed)
                .count();

        return (int) Math.round((passedCount * 100.0) / evaluations.size());
    }

    private List<DashboardActivityResponse> recentActivities() {
        return loginAuditRepository.findTop10ByOrderByOccurredAtDesc().stream()
                .map(this::toActivity)
                .toList();
    }

    private DashboardActivityResponse toActivity(LoginAudit audit) {
        String action = LOGIN_EVENT_LABELS.getOrDefault(audit.getEventType(), audit.getEventType());
        return new DashboardActivityResponse(
                audit.getOccurredAt().format(TIME_FORMAT),
                audit.getUser().getName(),
                action,
                "Authentification"
        );
    }
}