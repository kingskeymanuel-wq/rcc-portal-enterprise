package com.ecobank.rccportal.dto;

public record AgentTrainingStatsResponse(
        Long userId,
        String name,
        String username,
        String team,
        int lessonsCompleted,
        int totalLessons,
        double overallPercent,
        boolean fullyCompleted,
        String status
) {}
