package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/**
 * currentState : WORKING | ON_PAUSE | ON_LUNCH | ON_TRAINING | ON_MEETING | DISCONNECTED | SHIFT_ENDED | NOT_STARTED
 * since : début de l'état courant (continuité conservée à travers une déconnexion/reconnexion).
 */
public record LiveShiftStatusResponse(
        String username,
        String fullName,
        String team,
        String currentState,
        LocalDateTime since,
        LocalDateTime lastDisconnectedAt,
        LocalDateTime lastReconnectedAt,
        long absenceMinutesToday,
        int disconnectionCount
) {
}
