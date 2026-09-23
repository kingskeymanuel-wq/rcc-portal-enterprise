package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/** Vue QA d'un agent face à une évaluation précise — voir GameService.evaluationResultsFor(). */
public record GameEvaluationAgentResultResponse(
        Long userId,
        String username,
        String fullName,
        String team,
        boolean completed,
        Integer attemptNumber,
        Integer score,
        Integer correctCount,
        Integer totalCount,
        LocalDateTime lastAttemptAt
) {
}
