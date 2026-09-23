package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ShiftSwapResponse(
        Integer swapRequestId,
        String requesterUsername, String requesterName, LocalDate requesterDate,
        String requesterShiftCode, String requesterShiftLabel,
        String targetUsername, String targetName, LocalDate targetDate,
        String targetShiftCode, String targetShiftLabel,
        String team,
        String peerStatus,
        String teamLeaderStatus,
        String decidedByTeamLeaderUsername,
        String teamLeaderComment,
        String requesterMessage,
        LocalDateTime createdAt,
        LocalDateTime peerDecidedAt,
        LocalDateTime teamLeaderDecidedAt,
        boolean swapApplied
) {
}
