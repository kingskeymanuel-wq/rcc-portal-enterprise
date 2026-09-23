package com.ecobank.rccportal.dto;

public record CourseResponse(
        Integer courseId, String title, String description, String content,
        String type, Boolean mandatory, String videoUrl, Integer questionCount,
        String teamCode, String teamLabel, String createdByUsername, String createdByName,
        String imageUrl, String fileUrl, String fileName, String category,
        String publicationStatus, java.time.LocalDateTime publishedAt, String publishedBy) {
}
