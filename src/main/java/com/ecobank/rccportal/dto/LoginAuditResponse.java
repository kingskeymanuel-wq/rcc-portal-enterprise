package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record LoginAuditResponse(
        Integer id,
        String username,
        String fullName,
        String eventType,
        LocalDateTime occurredAt,
        Integer passwordAgeDays,
        String ipAddress
) {
}
