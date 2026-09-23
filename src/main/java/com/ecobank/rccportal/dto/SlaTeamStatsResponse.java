package com.ecobank.rccportal.dto;

public record SlaTeamStatsResponse(
        String team,
        String type,
        long decidedCount,
        double averageHours,
        long overdueCount,
        int thresholdHours
) {}
