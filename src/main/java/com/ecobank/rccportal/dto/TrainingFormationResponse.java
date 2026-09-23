package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record TrainingFormationResponse(
        Integer formationId,
        String title,
        String description,
        String category,
        LocalDate scheduledDate,
        LocalTime scheduledTime,
        String recurrenceType,
        Integer durationMinutes,
        Double videoMaxPlaybackRate,
        Integer completionThresholdPercent,
        String status,
        String targetTeam,
        Boolean mandatory,
        Integer lessonCount,
        Integer myProgressPercent
) {}
