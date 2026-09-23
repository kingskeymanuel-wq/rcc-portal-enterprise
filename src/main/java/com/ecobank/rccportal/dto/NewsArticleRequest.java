package com.ecobank.rccportal.dto;

public record NewsArticleRequest(String title, String contentHtml, String imageUrl, Integer sortOrder) {
}
