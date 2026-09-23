package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record RccPostResponse(
        Integer id, String authorMatricule, String authorLabel, String authorPhotoUrl,
        String content, String imageUrl, int viewCount,
        int likeCount, boolean likedByMe, int commentCount,
        LocalDateTime publishedAt, LocalDateTime updatedAt,
        LocalDateTime expiresAt, boolean scheduled) {
}
