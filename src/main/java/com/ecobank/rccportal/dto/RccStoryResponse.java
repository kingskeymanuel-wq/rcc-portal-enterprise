package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record RccStoryResponse(
        Integer id, String authorLabel, String content, String imageUrl,
        LocalDateTime publishedAt, LocalDateTime expiresAt) {
}
