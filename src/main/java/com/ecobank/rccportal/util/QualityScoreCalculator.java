package com.ecobank.rccportal.util;

import com.ecobank.rccportal.model.QualityCriterion;
import com.ecobank.rccportal.model.QualityEvaluationScore;

import java.util.List;

/**
 * Calcul de la note pondérée d'une évaluation Clairaudio — logique pure, sans
 * dépendance Spring, testable isolément. Reproduit exactement la règle du
 * frontend original (isKO() + moyenne pondérée sur les critères notés) :
 * - un critère éliminatoire (IsKnockOut) noté 0 fait échouer l'évaluation
 *   entière, quel que soit le pourcentage global ;
 * - le pourcentage ne prend en compte que les critères notés (scoreValue non
 *   null, isNotApplicable = false) ; un critère "N/A" est simplement exclu du
 *   calcul, ni pour ni contre.
 * - seuil de réussite : 80 % (aligné sur le paramétrage Clairaudio d'origine).
 */
public final class QualityScoreCalculator {

    public static final double PASS_THRESHOLD_PERCENT = 80.0;
    private static final int MAX_SCORE_PER_CRITERION = 2;

    private QualityScoreCalculator() {
    }

    public record Result(double scorePercentage, boolean passed, boolean knockedOut) {
    }

    public static Result compute(List<QualityEvaluationScore> scores) {
        boolean knockedOut = scores.stream()
                .anyMatch(s -> Boolean.TRUE.equals(s.getCriterion().getIsKnockOut())
                        && !Boolean.TRUE.equals(s.getIsNotApplicable())
                        && s.getScoreValue() != null && s.getScoreValue() == 0);

        double weightedObtained = 0;
        double weightedMax = 0;
        for (QualityEvaluationScore score : scores) {
            if (Boolean.TRUE.equals(score.getIsNotApplicable()) || score.getScoreValue() == null) continue;
            QualityCriterion criterion = score.getCriterion();
            weightedObtained += score.getScoreValue() * criterion.getWeight();
            weightedMax += MAX_SCORE_PER_CRITERION * criterion.getWeight();
        }

        double percentage = weightedMax == 0 ? 0.0 : (weightedObtained / weightedMax) * 100.0;
        boolean passed = !knockedOut && percentage >= PASS_THRESHOLD_PERCENT;
        return new Result(round2(percentage), passed, knockedOut);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
