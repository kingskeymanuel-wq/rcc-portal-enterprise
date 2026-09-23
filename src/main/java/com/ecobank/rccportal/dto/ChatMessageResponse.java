package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        Integer id, Integer conversationId, String senderMatricule, String senderName, String senderPhotoUrl,
        String content, String mediaUrl, LocalDateTime expiresAt, LocalDateTime sentAt, boolean mine) {
}
