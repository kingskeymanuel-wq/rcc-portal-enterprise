package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record ActiveSessionResponse(
        Integer refreshTokenId,
        LocalDateTime connectedAt,
        LocalDateTime expiresAt
) {
}
