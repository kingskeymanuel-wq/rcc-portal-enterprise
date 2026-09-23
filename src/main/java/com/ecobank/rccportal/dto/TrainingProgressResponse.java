package com.ecobank.rccportal.dto;

public record TrainingProgressResponse(
        Integer lessonId,
        Integer scrollPercent,
        Integer videoWatchedPercent,
        Integer overallPercent,
        Boolean completed,
        Integer speedViolationCount
) {}
