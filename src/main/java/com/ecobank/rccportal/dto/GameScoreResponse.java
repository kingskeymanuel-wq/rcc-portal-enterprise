package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record GameScoreResponse(
        String playerName,
        Integer score,
        Integer correctCount,
        Integer totalCount,
        LocalDateTime playedAt
) {}
