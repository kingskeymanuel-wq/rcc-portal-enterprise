package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** Ces contraintes ne s'appliquent qu'à la création (@Valid n'est posé que sur
 * QualityEvaluationController.create) — update() reste une mise à jour partielle, champs optionnels. */
public record QualityEvaluationRequest(
        @NotBlank(message = "agentMatricule is required.") String agentMatricule,
        @NotNull(message = "evaluationDate is required.") LocalDate evaluationDate,
        @NotNull(message = "callDate is required.") LocalDate callDate,
        String recordingRef,
        Integer durationMinutes,
        Integer motifId,
        String strengths,
        String improvements,
        String comment,
        String feedbackStatus,
        LocalDate feedbackDate,
        List<ScoreEntryDto> scores) {
}
