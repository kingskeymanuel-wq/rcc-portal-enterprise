package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record RccNotificationResponse(
        Integer id, String content, boolean isRead, LocalDateTime createdAt,
        String actionType, String actionTarget) {
}
