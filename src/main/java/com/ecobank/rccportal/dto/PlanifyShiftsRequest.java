package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Planning d'équipe saisi par le Team Leader : il coche des agents et assigne à CHACUN un shift pour
 * une période donnée (voir ScheduleService.submitTeamPlanning) — publié directement.
 * Un shift différent par agent est explicitement supporté — chaque entrée de la liste
 * porte son propre shiftCode.
 */
public record PlanifyShiftsRequest(
        LocalDate periodFrom,
        LocalDate periodTo,
        List<AgentShiftAssignment> assignments,
        /** Jours précis choisis dans le calendrier (sur plusieurs semaines ou mois) ; vide = tous les jours de la période. */
        List<LocalDate> days,
        /** true : les jours NON choisis entre le premier et le dernier jour choisi passent en repos (OFF). */
        Boolean restOnOtherDays
) {

    public PlanifyShiftsRequest(LocalDate periodFrom, LocalDate periodTo, List<AgentShiftAssignment> assignments) {
        this(periodFrom, periodTo, assignments, null, null);
    }

    /** shiftCode doit être un des 4 shifts fixes de ScheduleService.FIXED_SHIFTS ("M","M2","A","N"),
     *  ou "OFF" pour retirer un agent du planning de cette période sans lui assigner d'horaire. */
    public record AgentShiftAssignment(String username, String shiftCode) {
    }
}
