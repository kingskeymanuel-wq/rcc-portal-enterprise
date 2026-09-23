package com.ecobank.rccportal.dto;

import java.math.BigDecimal;

public record ProductivityRankingEntry(
        String username,
        String fullName,
        String team,
        BigDecimal value,
        int rank
) {
}
