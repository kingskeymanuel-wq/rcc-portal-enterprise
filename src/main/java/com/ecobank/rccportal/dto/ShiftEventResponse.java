package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record ShiftEventResponse(
        Integer shiftEventId, String username, String userFullName,
        String service, String affiliateBranch, String activity,
        String eventType, LocalDateTime occurredAt) {
}
