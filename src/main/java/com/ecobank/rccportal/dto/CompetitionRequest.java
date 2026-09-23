package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

/** teams : codes TeamClassifier (INBOUND_VOICE, CIB...) — ignoré si isTraineeOnly=true. */
public record CompetitionRequest(
        String gameKey,
        String title,
        LocalDateTime scheduledAt,
        boolean isTraineeOnly,
        List<String> teams
) {
}
