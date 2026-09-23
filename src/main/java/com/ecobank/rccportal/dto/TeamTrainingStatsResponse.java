package com.ecobank.rccportal.dto;

public record TeamTrainingStatsResponse(
        String team,
        int agentCount,
        int agentsCompletedCount,
        int agentsNotStartedCount,
        double averagePercent
) {}
