package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record AppointmentRequest(
        String clientName,
        String clientPhone,
        String purpose,
        LocalDateTime scheduledAt,
        String status,
        String notes
) {
}
