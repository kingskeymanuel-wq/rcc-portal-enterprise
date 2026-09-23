package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record MailTemplateResponse(
        Integer id,
        Integer categoryId,
        String subject,
        String body,
        String recipientType,
        Integer recipientGroupId,
        String createdByUserId,
        Boolean isSystemTemplate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
