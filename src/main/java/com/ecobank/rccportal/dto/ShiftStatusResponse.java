package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * currentState : WORKING | ON_PAUSE | ON_LUNCH | ON_TRAINING | ON_MEETING | DISCONNECTED | SHIFT_ENDED | NOT_STARTED
 *
 * @param currentStateSince           début de l'état courant — conservé à travers une déconnexion/reconnexion
 *                                    le même jour (minuteur en continuité)
 * @param lastDisconnectedAt          dernière déconnexion du jour (null si aucune)
 * @param lastReconnectedAt           dernière reconnexion après déconnexion (null si aucune)
 * @param absenceMinutesToday         total des absences pour déconnexion aujourd'hui
 * @param absenceMinutesInCurrentState part d'absence incluse dans le minuteur courant
 */
public record ShiftStatusResponse(
        String currentState,
        List<ShiftEventResponse> todayEvents,
        LocalDateTime currentStateSince,
        LocalDateTime lastDisconnectedAt,
        LocalDateTime lastReconnectedAt,
        long absenceMinutesToday,
        long absenceMinutesInCurrentState,
        List<ShiftAbsenceResponse> absences
) {
}
