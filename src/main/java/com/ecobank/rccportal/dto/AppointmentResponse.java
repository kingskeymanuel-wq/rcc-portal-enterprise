package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record AppointmentResponse(
        Integer appointmentId,
        Long agentUserId,
        String agentName,
        String clientName,
        String clientPhone,
        String purpose,
        LocalDateTime scheduledAt,
        String status,
        String notes,
        LocalDateTime createdAt
) {
}
