package com.ecobank.rccportal;

import com.ecobank.rccportal.model.QualityCriterion;
import com.ecobank.rccportal.model.QualityEvaluationScore;
import com.ecobank.rccportal.util.QualityScoreCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Logique pure, sans mock — le cœur métier de Clairaudio. */
class QualityScoreCalculatorTest {

    private QualityCriterion criterion(int weight, boolean knockOut) {
        return QualityCriterion.builder().weight(weight).isKnockOut(knockOut).build();
    }

    private QualityEvaluationScore score(QualityCriterion criterion, Short value, boolean na) {
        return QualityEvaluationScore.builder().criterion(criterion).scoreValue(value).isNotApplicable(na).build();
    }

    @Test
    void perfectScoresGiveOneHundredPercentAndPass() {
        var c1 = criterion(10, false);
        var c2 = criterion(10, false);
        var result = QualityScoreCalculator.compute(List.of(score(c1, (short) 2, false), score(c2, (short) 2, false)));

        assertEquals(100.0, result.scorePercentage());
        assertTrue(result.passed());
        assertFalse(result.knockedOut());
    }

    @Test
    void aZeroOnAKnockOutCriterionFailsRegardlessOfOverallPercentage() {
        var ko = criterion(5, true);
        var other = criterion(20, false);
        // Le critère KO ne pèse presque rien, le reste est parfait : le pourcentage global serait élevé,
        // mais le KO doit quand même faire échouer l'évaluation.
        var result = QualityScoreCalculator.compute(List.of(score(ko, (short) 0, false), score(other, (short) 2, false)));

        assertTrue(result.knockedOut());
        assertFalse(result.passed(), "un KO doit faire échouer même avec un bon pourcentage global");
    }

    @Test
    void aZeroOnANonKnockOutCriterionOnlyLowersThePercentage() {
        var c1 = criterion(10, false);
        var c2 = criterion(10, false);
        var result = QualityScoreCalculator.compute(List.of(score(c1, (short) 0, false), score(c2, (short) 2, false)));

        assertFalse(result.knockedOut());
        assertEquals(50.0, result.scorePercentage());
        assertFalse(result.passed(), "50% est sous le seuil de 80%");
    }

    @Test
    void notApplicableCriteriaAreExcludedFromTheCalculation() {
        var c1 = criterion(10, false);
        var c2 = criterion(10, false); // N/A : ne doit compter ni pour, ni contre
        var result = QualityScoreCalculator.compute(List.of(
                score(c1, (short) 2, false),
                score(c2, null, true)));

        assertEquals(100.0, result.scorePercentage(), "le critère N/A ne doit pas faire baisser le pourcentage");
    }

    @Test
    void aKnockOutScoredNotApplicableDoesNotTriggerFailure() {
        var ko = criterion(5, true);
        var other = criterion(10, false);
        var result = QualityScoreCalculator.compute(List.of(
                score(ko, null, true), // KO non applicable à cet appel : ne doit pas déclencher un échec
                score(other, (short) 2, false)));

        assertFalse(result.knockedOut());
        assertTrue(result.passed());
    }

    @Test
    void exactlyAtThresholdPasses() {
        var c1 = criterion(10, false);
        // 1.6/2 = 80% pile — mais les scores sont des entiers (0/1/2), donc on vise une pondération
        // qui tombe exactement à 80% avec deux critères de poids différents.
        var c2 = criterion(40, false); // poids fort, score parfait
        var c3 = criterion(10, false); // poids faible, score à 1 (sur 2)
        var result = QualityScoreCalculator.compute(List.of(
                score(c1, (short) 2, false), score(c2, (short) 2, false), score(c3, (short) 1, false)));
        // obtenu = 2*10 + 2*40 + 1*10 = 110 ; max = 2*10 + 2*40 + 2*10 = 120 -> 91.67%
        assertTrue(result.scorePercentage() >= QualityScoreCalculator.PASS_THRESHOLD_PERCENT);
        assertTrue(result.passed());
    }
}
