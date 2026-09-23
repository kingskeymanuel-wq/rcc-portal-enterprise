package com.ecobank.rccportal.dto;

import java.time.OffsetDateTime;

public record PendingAccountResponse(
        String matricule,
        String name,
        String email,
        String team,
        OffsetDateTime requestedAt
) {
}