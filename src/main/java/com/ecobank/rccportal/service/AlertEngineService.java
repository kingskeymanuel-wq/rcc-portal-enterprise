package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceAlertResponse;
import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.model.PerformanceAlert;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.PerformanceAlertRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.TeamClassifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Détection automatique d'anomalies de performance — inspiré du concept "Alert Engine"
 * partagé par l'utilisateur (agent_kpi / seuils AHT-CSAT d'un centre d'appel classique),
 * mais réécrit sur les métriques que ce portail suit réellement : présence, score QA,
 * score d'évaluation (voir GameEvaluationAttempt), performance globale calculée
 * (PerformanceService). Aucune dépendance à un fournisseur d'IA externe — seuils fixes,
 * comme demandé.
 *
 * Lancé chaque nuit par PerformanceAlertScheduler, ou à la demande (QA/Admin) via
 * DataAnalysisController. Idempotent : ne recrée pas une alerte déjà émise pour le même
 * agent, le même mois et le même type (voir existsByUserIdAndPeriodMonthAndAlertType).
 */
@Service
public class AlertEngineService {

    private final ReportingService reportingService;
    private final PerformanceAlertRepository alertRepository;
    private final UserRepository userRepository;

    public AlertEngineService(ReportingService reportingService, PerformanceAlertRepository alertRepository,
                              UserRepository userRepository) {
        this.reportingService = reportingService;
        this.alertRepository = alertRepository;
        this.userRepository = userRepository;
    }

    /** Analyse tous les agents pour ce mois et enregistre les nouvelles alertes détectées. Renvoie le nombre créé. */
    private static final double SIGNIFICANT_DROP = 15.0; // points de pourcentage — seuil pour une "chute significative"

    @Transactional
    public int detectForMonth(YearMonth month) {
        String periodMonth = month.toString();
        List<PerformanceResponse> rows = reportingService.teamSummary(month);

        // Mois précédent, indexé par agent — pour la comparaison d'évolution.
        java.util.Map<Long, PerformanceResponse> previousByUser = new java.util.HashMap<>();
        for (PerformanceResponse p : reportingService.teamSummary(month.minusMonths(1))) {
            if (p.userId() != null) previousByUser.put(p.userId(), p);
        }

        int created = 0;

        for (PerformanceResponse r : rows) {
            if (r.userId() == null) continue;

            created += raise(r.userId(), periodMonth, "PRESENCE_FAIBLE",
                    r.presenceRate() < 70 ? "CRITIQUE" : (r.presenceRate() < 85 ? "ATTENTION" : null),
                    "Présence à " + Math.round(r.presenceRate()) + " % ce mois-ci — sous le seuil attendu.");

            if (r.avgQualityScore() != null) {
                created += raise(r.userId(), periodMonth, "SCORE_QA_FAIBLE",
                        r.avgQualityScore() < 50 ? "CRITIQUE" : (r.avgQualityScore() < 70 ? "ATTENTION" : null),
                        "Score qualité moyen à " + Math.round(r.avgQualityScore()) + " % — sous le seuil de 70 %.");
            }

            Double scoreEvaluation = r.kpiMetrics() != null ? r.kpiMetrics().get("SCORE_EVALUATION") : null;
            if (scoreEvaluation != null) {
                created += raise(r.userId(), periodMonth, "EVALUATION_FAIBLE",
                        scoreEvaluation < 50 ? "ATTENTION" : null,
                        "Score d'évaluation à " + Math.round(scoreEvaluation) + " / 100 — en dessous de la moyenne attendue.");
            }

            if (r.performanceGlobale() != null) {
                created += raise(r.userId(), periodMonth, "PERFORMANCE_EN_BAISSE",
                        r.performanceGlobale() < 60 ? "ATTENTION" : null,
                        "Performance globale à " + Math.round(r.performanceGlobale()) + " % ce mois-ci.");
            }

            // ---------- Comparaison au mois précédent ----------
            PerformanceResponse previous = previousByUser.get(r.userId());
            if (previous == null) continue; // pas de référence (nouvel agent, ou aucune donnée le mois dernier)

            created += raiseIfDrop(r.userId(), periodMonth, "CHUTE_PRESENCE",
                    previous.presenceRate(), r.presenceRate(), "Présence");

            if (previous.avgQualityScore() != null && r.avgQualityScore() != null) {
                created += raiseIfDrop(r.userId(), periodMonth, "CHUTE_SCORE_QA",
                        previous.avgQualityScore(), r.avgQualityScore(), "Score QA");
            }

            if (previous.performanceGlobale() != null && r.performanceGlobale() != null) {
                created += raiseIfDrop(r.userId(), periodMonth, "CHUTE_PERFORMANCE",
                        previous.performanceGlobale(), r.performanceGlobale(), "Performance globale");
            }
        }
        return created;
    }

    /** Chute d'au moins SIGNIFICANT_DROP points par rapport au mois précédent — ATTENTION en dessous, CRITIQUE au-delà de 2x le seuil. */
    private int raiseIfDrop(Long userId, String periodMonth, String alertType, double previousValue, double currentValue, String metricLabel) {
        double drop = previousValue - currentValue;
        if (drop < SIGNIFICANT_DROP) return 0;
        String severity = drop >= SIGNIFICANT_DROP * 2 ? "CRITIQUE" : "ATTENTION";
        String message = metricLabel + " en chute : " + Math.round(previousValue) + " % le mois dernier → "
                + Math.round(currentValue) + " % ce mois-ci (−" + Math.round(drop) + " points).";
        return raise(userId, periodMonth, alertType, severity, message);
    }

    /** Crée l'alerte si severity != null (seuil dépassé) et qu'elle n'existe pas déjà pour ce mois. */
    private int raise(Long userId, String periodMonth, String alertType, String severity, String message) {
        if (severity == null) return 0;
        if (alertRepository.existsByUserIdAndPeriodMonthAndAlertType(userId, periodMonth, alertType)) return 0;
        alertRepository.save(PerformanceAlert.builder()
                .userId(userId)
                .periodMonth(periodMonth)
                .alertType(alertType)
                .severity(severity)
                .message(message)
                .acknowledged(false)
                .build());
        return 1;
    }

    @Transactional(readOnly = true)
    public List<PerformanceAlertResponse> listForMonth(YearMonth month) {
        return toResponses(alertRepository.findByPeriodMonthOrderByCreatedAtDesc(month.toString()));
    }

    /** Alertes du mois, filtrées sur une équipe précise — pour le Team Leader (ne voit que la sienne). */
    @Transactional(readOnly = true)
    public List<PerformanceAlertResponse> listForMonthAndTeam(YearMonth month, TeamClassifier.Team team) {
        return listForMonth(month).stream()
                .filter(a -> team.name().equals(a.team()))
                .toList();
    }

    /** Historique complet d'un agent — TOUTES ses alertes, tous les mois confondus, la plus
     *  récente d'abord — pour visualiser l'évolution de sa performance depuis son arrivée. */
    @Transactional(readOnly = true)
    public List<PerformanceAlertResponse> historyForUser(Long userId) {
        return toResponses(alertRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @Transactional
    public void acknowledge(Integer alertId, String username) {
        alertRepository.findById(alertId).ifPresent(a -> {
            a.setAcknowledged(true);
            a.setAcknowledgedByUsername(username);
            alertRepository.save(a);
        });
    }

    private List<PerformanceAlertResponse> toResponses(List<PerformanceAlert> alerts) {
        List<PerformanceAlertResponse> out = new ArrayList<>();
        for (PerformanceAlert a : alerts) {
            User u = userRepository.findById(a.getUserId()).orElse(null);
            String team = u != null ? TeamClassifier.classify(u.getActivity()).name() : null;
            out.add(new PerformanceAlertResponse(a.getAlertId(), a.getUserId(),
                    u != null ? (u.getName() != null ? u.getName() : u.getUsername()) : "Agent supprimé",
                    team, a.getPeriodMonth(), a.getAlertType(), a.getSeverity(), a.getMessage(),
                    a.getAcknowledged(), a.getAcknowledgedByUsername(), a.getCreatedAt()));
        }
        return out;
    }
}
