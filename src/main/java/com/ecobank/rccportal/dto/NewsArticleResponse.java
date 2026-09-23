package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record NewsArticleResponse(Integer newsId, String title, String contentHtml, String imageUrl,
                                  String createdByName, Integer sortOrder,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
}
