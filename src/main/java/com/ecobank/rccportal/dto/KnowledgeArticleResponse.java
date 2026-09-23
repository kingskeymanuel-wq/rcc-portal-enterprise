package com.ecobank.rccportal.dto;
import java.time.LocalDateTime;

public record KnowledgeArticleResponse(Integer articleId, Integer categoryId, String categoryTitle,
                                       String countryCode, String countryLabel,
                                       String serviceCode, String serviceName,
                                       String title, String contentHtml, String tags, Integer sortOrder,
                                       LocalDateTime createdAt, LocalDateTime updatedAt) {}