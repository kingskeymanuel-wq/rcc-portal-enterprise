package com.ecobank.rccportal.dto;

public record QuizAnswerResult(
        Integer questionId,
        Boolean correct,
        Integer correctOptionIndex,
        String explanation,
        Integer pointsEarned
) {}
