package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** type: "LATE" | "ABSENCE". justified obligatoire ; reason recommandé si justified=true. */
public record AttendanceFollowUpRequest(
        String agentUsername,
        String type,
        LocalDate relatedDate,
        Boolean justified,
        String reason
) {
}
