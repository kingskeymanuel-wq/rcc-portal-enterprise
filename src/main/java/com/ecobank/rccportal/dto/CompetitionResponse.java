package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CompetitionResponse(
        Integer competitionId,
        String gameKey,
        String gameTitle,
        String title,
        LocalDateTime scheduledAt,
        String status,
        boolean isTraineeOnly,
        String createdByUsername,
        LocalDateTime createdAt,
        List<CompetitionTeamResponse> teams
) {
    public record CompetitionTeamResponse(
            String team,
            String teamLabel,
            boolean validated,
            String validatedByUsername,
            List<CompetitionParticipantResponse> participants
    ) {
    }

    public record CompetitionParticipantResponse(
            Long userId,
            String username,
            String fullName,
            Integer score,
            boolean completed
    ) {
    }
}
