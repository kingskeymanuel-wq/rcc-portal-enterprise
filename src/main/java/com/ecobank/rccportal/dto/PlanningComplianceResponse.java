package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Présence réelle d'un agent comparée à SON planning du jour (AgentSchedule validé).
 *
 * @param status ON_TIME | LATE | ABSENT | NOT_YET (heure de prise de poste pas encore dépassée) |
 *               OFF (repos planifié) | LEAVE (congé approuvé ou code congé/maladie) |
 *               PLANNED_ABSENCE (code ABS au planning) | UNPLANNED (connecté sans planning validé)
 * @param lateMinutes         retard à la première connexion (null si à l'heure / non applicable)
 * @param earlyLeaveMinutes   départ anticipé : fin de shift avant l'heure planifiée (null sinon)
 * @param disconnectedMinutes absences pour déconnexion en cours de shift (voir ShiftTimeline)
 * @param swapped             le shift du jour vient d'une permutation validée par le Team Leader
 * @param detail              phrase lisible, prête à afficher
 */
public record PlanningComplianceResponse(
        String username,
        String fullName,
        String team,
        LocalDate date,
        String shiftCode,
        String shiftLabel,
        LocalTime plannedStart,
        LocalTime plannedEnd,
        boolean overnight,
        LocalTime firstLogin,
        LocalTime shiftEnd,
        String status,
        Integer lateMinutes,
        Integer earlyLeaveMinutes,
        long disconnectedMinutes,
        boolean swapped,
        String detail
) {
}
