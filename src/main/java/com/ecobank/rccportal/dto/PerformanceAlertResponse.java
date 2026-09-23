package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record PerformanceAlertResponse(
        Integer alertId,
        Long userId,
        String userFullName,
        String team,
        String periodMonth,
        String alertType,
        String severity,
        String message,
        Boolean acknowledged,
        String acknowledgedByUsername,
        LocalDateTime createdAt
) {
}
