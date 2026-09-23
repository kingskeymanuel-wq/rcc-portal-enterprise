package com.ecobank.rccportal.dto;

public record TrainingLessonResponse(
        Integer lessonId,
        Integer formationId,
        String title,
        String contentHtml,
        String videoUrl,
        Integer orderIndex,
        Integer estimatedMinutes,
        Double videoMaxPlaybackRate,
        Integer scrollPercent,
        Integer videoWatchedPercent,
        Boolean completed,
        String formationTitle,
        Integer prevLessonId,
        Integer nextLessonId
) {}
