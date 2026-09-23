package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.PerformanceResponse;
import com.ecobank.rccportal.dto.QualityEvaluationResponse;
import com.ecobank.rccportal.model.ManualKpiEntry;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.ManualKpiEntryRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indicateurs personnels d'un agent pour un mois donné : KPI saisis
 * manuellement (dernière saisie du mois par métrique), score qualité moyen
 * (évaluations Clairaudio du mois), taux de présence, et un score global
 * (performanceGlobale) = la rubrique SCORE_QA du mois telle qu'importée,
 * directement — reste null si cette métrique n'a pas été importée pour le
 * mois demandé, jamais un score approximatif silencieux.
 */
@Service
public class PerformanceService {

    /** Rubrique KPI qui définit performanceGlobale — décision du 13/08/2026 : Score QA du mois, telle quelle. */
    private static final String METRIC_SCORE_QA = "SCORE_QA";

    private final UserRepository userRepository;
    private final ManualKpiEntryRepository manualKpiEntryRepository;
    private final QualityEvaluationService qualityEvaluationService;
    private final ShiftService shiftService;
    private final com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final com.ecobank.rccportal.repository.GameEvaluationAttemptRepository gameEvaluationAttemptRepository;

    public PerformanceService(
            UserRepository userRepository,
            ManualKpiEntryRepository manualKpiEntryRepository,
            QualityEvaluationService qualityEvaluationService,
            ShiftService shiftService,
            com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository,
            com.ecobank.rccportal.repository.GameEvaluationAttemptRepository gameEvaluationAttemptRepository) {

        this.userRepository = userRepository;
        this.manualKpiEntryRepository = manualKpiEntryRepository;
        this.qualityEvaluationService = qualityEvaluationService;
        this.shiftService = shiftService;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
        this.gameEvaluationAttemptRepository = gameEvaluationAttemptRepository;
    }

    @Transactional(readOnly = true)
    public PerformanceResponse computeFor(String username, YearMonth month) {
        YearMonth targetMonth = month != null ? month : YearMonth.now();
        return computeFor(username, targetMonth.atDay(1), targetMonth.atEndOfMonth(), targetMonth.toString());
    }

    /**
     * Version généralisée — même calcul que computeFor(username, YearMonth), mais sur une
     * plage de dates arbitraire (jour, semaine, mois ou toute autre période), pour le
     * Reporting/Portail Superviseur — voir ReportingService.teamSummary(LocalDate, LocalDate, String).
     */
    @Transactional(readOnly = true)
    public PerformanceResponse computeFor(String username, LocalDate periodStart, LocalDate periodEnd, String periodLabel) {

        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));

        LocalDate monthStart = periodStart;
        LocalDate monthEnd = periodEnd;

        Map<String, Double> kpiMetrics = latestMetricsInRange(user, monthStart, monthEnd);

        Double evaluationScore = latestEvaluationScoreForMonth(user, monthStart, monthEnd);
        if (evaluationScore != null) {
            kpiMetrics = new HashMap<>(kpiMetrics);
            kpiMetrics.put("SCORE_EVALUATION", evaluationScore);
        }

        List<QualityEvaluationResponse> evaluations = qualityEvaluationService.listForAgent(username).stream()
                .filter(e -> !e.evaluationDate().isBefore(monthStart) && !e.evaluationDate().isAfter(monthEnd))
                .toList();

        int evaluationCount = evaluations.size();
        Double avgQualityScore = null;
        if (!evaluations.isEmpty()) {
            double sum = 0.0;
            for (QualityEvaluationResponse e : evaluations) {
                sum += e.scorePercentage();
            }
            avgQualityScore = round2(sum / evaluations.size());
        }

        double presenceRate = round2(shiftService.computePresenceRate(username, monthStart, monthEnd));

        Double performanceGlobale = computeGlobalScore(kpiMetrics);

        return new PerformanceResponse(
                user.getUsername(),
                user.getName(),
                periodLabel,
                evaluationCount,
                avgQualityScore,
                kpiMetrics,
                presenceRate,
                performanceGlobale,
                user.getAffiliateBranch(),
                getPrimaryServiceName(user),
                user.getActivity(),
                user.getId()
        );
    }

    /**
     * Score (%) de la tentative d'évaluation QCM la plus récente de l'agent dans le mois
     * demandé — voir GameEvaluationAttempt / games.js resultScreen(). null si aucune
     * tentative dans ce mois (jamais un score approximatif silencieux, même logique que
     * performanceGlobale ci-dessus).
     */
    private Double latestEvaluationScoreForMonth(User user, LocalDate monthStart, LocalDate monthEnd) {
        if (user.getId() == null) return null;
        return gameEvaluationAttemptRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(a -> {
                    LocalDate d = a.getCreatedAt().toLocalDate();
                    return !d.isBefore(monthStart) && !d.isAfter(monthEnd);
                })
                .findFirst()
                .filter(a -> a.getCorrectCount() != null && a.getTotalCount() != null && a.getTotalCount() > 0)
                .map(a -> round2(100.0 * a.getCorrectCount() / a.getTotalCount()))
                .orElse(null);
    }

    private String getPrimaryServiceName(User user) {
        if (user == null || user.getId() == null) return null;
        var assignments = userServiceAssignmentRepository.findServicesByUserId(user.getId());
        if (assignments == null || assignments.isEmpty() || assignments.get(0).getService() == null) return null;
        return assignments.get(0).getService().getName();
    }

    /** Dernière saisie (par CreatedAt) de chaque métrique dont PeriodDate tombe dans la plage demandée. */
    private Map<String, Double> latestMetricsInRange(User user, LocalDate rangeStart, LocalDate rangeEnd) {
        Map<String, ManualKpiEntry> latestByMetric = new HashMap<>();

        for (ManualKpiEntry entry : manualKpiEntryRepository.findBySubjectOrderByPeriodDateDesc(user)) {
            LocalDate d = entry.getPeriodDate();
            if (d.isBefore(rangeStart) || d.isAfter(rangeEnd)) {
                continue;
            }
            ManualKpiEntry current = latestByMetric.get(entry.getMetricCode());
            if (current == null || entry.getCreatedAt().isAfter(current.getCreatedAt())) {
                latestByMetric.put(entry.getMetricCode(), entry);
            }
        }

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, ManualKpiEntry> e : latestByMetric.entrySet()) {
            result.put(e.getKey(), e.getValue().getMetricValue().doubleValue());
        }
        return result;
    }

    private Double computeGlobalScore(Map<String, Double> kpiMetrics) {
        Double scoreQa = kpiMetrics.get(METRIC_SCORE_QA);
        return scoreQa != null ? round2(scoreQa) : null;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
