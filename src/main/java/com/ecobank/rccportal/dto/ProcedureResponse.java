package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProcedureResponse(
        Integer id,
        String zoneCode,
        String serviceCode,
        String serviceName,
        String countryCode,
        String title,
        String slaDelay,
        String level,
        String responsibleTeam,
        List<String> steps,
        List<AttachmentResponse> attachments,
        String createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
