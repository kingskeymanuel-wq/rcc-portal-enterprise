package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Planification directe (sans fichier) depuis la modale "Planifier" — le prestataire
 * (Excelliam) choisit une équipe, coche des agents, et assigne à CHACUN un shift
 * individuellement pour une période donnée (voir ScheduleService.planifyShifts).
 * Un shift différent par agent est explicitement supporté — chaque entrée de la liste
 * porte son propre shiftCode.
 */
public record PlanifyShiftsRequest(
        LocalDate periodFrom,
        LocalDate periodTo,
        List<AgentShiftAssignment> assignments
) {
    /** shiftCode doit être un des 4 shifts fixes de ScheduleService.FIXED_SHIFTS ("M","M2","A","N"),
     *  ou "OFF" pour retirer un agent du planning de cette période sans lui assigner d'horaire. */
    public record AgentShiftAssignment(String username, String shiftCode) {
    }
}
