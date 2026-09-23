package com.ecobank.rccportal.dto;

import java.util.List;

/**
 * Statut d'une évaluation QCM pour l'utilisateur courant — voir GameService.evaluationStatus().
 * completed = true seulement si les 2 tentatives ont déjà été jouées pour la session
 * d'évaluation EN COURS (EvaluationRound) : dans ce cas, le frontend réaffiche directement
 * le résultat final (finalScore/finalAnswers) au lieu de relancer une partie.
 */
public record GameEvaluationStatusResponse(
        boolean completed,
        Integer finalScore,
        Integer finalCorrectCount,
        Integer finalTotalCount,
        List<GameEvaluationResultRequest.AnswerDetail> finalAnswers
) {
    public static GameEvaluationStatusResponse notCompleted() {
        return new GameEvaluationStatusResponse(false, null, null, null, null);
    }
}
