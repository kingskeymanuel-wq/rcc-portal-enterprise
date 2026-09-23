package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record AttachmentResponse(
        Integer id, String entityType, Integer entityId, String fileName,
        String mimeType, String storageUrl, String uploadedByUserId, LocalDateTime createdAt,
        boolean isFavorite) {
}
