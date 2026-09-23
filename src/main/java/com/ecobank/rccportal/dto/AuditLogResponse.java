package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record AuditLogResponse(
        Integer id,
        String username,
        String action,
        String details,
        LocalDateTime createdAt
) {
}
