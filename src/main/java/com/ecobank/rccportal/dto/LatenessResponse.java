package com.ecobank.rccportal.dto;

import java.time.LocalTime;

/** actualLoginTime null = pas encore connecté (retard potentiel en cours, pas encore confirmé).
 *  shiftCode/shiftLabel : le shift précis attribué à CET agent CE jour-là (voir AgentSchedule)
 *  — chaque agent peut avoir un shift différent le même jour, jamais un horaire unique appliqué
 *  à toute l'équipe. */
public record LatenessResponse(
        String username,
        String fullName,
        String service,
        String shiftCode,
        String shiftLabel,
        LocalTime plannedStartTime,
        LocalTime actualLoginTime,
        Integer lateMinutes
) {
}
