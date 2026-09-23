package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record CourseAttemptResponse(
        Integer attemptId, Integer courseId, String courseTitle, String username, String userFullName,
        String status, Integer score, Map<String, Integer> answers, LocalDateTime completedAt,
        Integer attemptNumber, boolean finalized, String teamCode, String teamLabel) {
}
