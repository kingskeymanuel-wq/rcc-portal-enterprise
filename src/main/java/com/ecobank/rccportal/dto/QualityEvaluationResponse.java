package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record QualityEvaluationResponse(
        Integer id,
        String agentMatricule, String agentName,
        String evaluatorMatricule,
        LocalDate evaluationDate, LocalDate callDate,
        String recordingRef, Integer durationMinutes,
        Integer motifId, String motifLabel,
        String strengths, String improvements, String comment,
        String feedbackStatus, LocalDate feedbackDate,
        List<ScoreEntryDto> scores,
        double scorePercentage, boolean passed, boolean knockedOut,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
