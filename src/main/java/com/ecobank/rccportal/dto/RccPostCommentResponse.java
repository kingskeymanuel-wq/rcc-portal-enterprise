package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record RccPostCommentResponse(
        Integer id, Integer postId, String authorMatricule, String authorLabel, String authorPhotoUrl,
        String content, LocalDateTime createdAt) {
}
