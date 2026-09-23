package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

public record QuizQuestionResponse(
        Integer questionId,
        String questionText,
        String type,
        String difficulty,
        String category,
        List<String> options,
        Integer correctOptionIndex,
        List<Integer> correctIndexes,
        String explanation,
        String imageUrl,
        String videoUrl,
        String tags,
        Integer points,
        Integer timeLimitSeconds,
        Boolean active,
        Integer usageCount,
        Integer correctAnswerCount,
        LocalDateTime createdAt
) {}
