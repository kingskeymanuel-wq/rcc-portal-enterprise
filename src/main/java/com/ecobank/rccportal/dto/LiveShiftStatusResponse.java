package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/**
 * currentState : WORKING | ON_PAUSE | ON_LUNCH | ON_TRAINING | ON_MEETING | DISCONNECTED | SHIFT_ENDED | NOT_STARTED
 * since : début de l'état courant (continuité conservée à travers une déconnexion/reconnexion).
 * plannedEnd : fin prévue du shift du jour — au-delà, l'agent encore en poste est en débordement.
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
        int disconnectionCount,
        LocalDateTime plannedEnd
) {
    /** Compatibilité : statut sans fin de shift connue. */
    public LiveShiftStatusResponse(String username, String fullName, String team, String currentState, LocalDateTime since,
                                   LocalDateTime lastDisconnectedAt, LocalDateTime lastReconnectedAt, long absenceMinutesToday,
                                   int disconnectionCount) {
        this(username, fullName, team, currentState, since, lastDisconnectedAt, lastReconnectedAt, absenceMinutesToday,
                disconnectionCount, null);
    }

    /** Shift encore ouvert après sa fin prévue (oubli de « Fin de shift »). */
    public boolean overflowing() {
        return com.ecobank.rccportal.util.ShiftOverflow.overflowing(currentState, plannedEnd, LocalDateTime.now());
    }
}
