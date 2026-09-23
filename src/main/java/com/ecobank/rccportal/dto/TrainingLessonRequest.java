package com.ecobank.rccportal.dto;

public record TrainingLessonRequest(
        String title,
        String contentHtml,
        String videoUrl,
        Integer orderIndex,
        Integer estimatedMinutes
) {}
