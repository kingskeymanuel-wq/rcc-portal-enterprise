package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** Décision d'Excelliam sur le planning soumis par un Team Leader (voir
 *  ScheduleService.decideExcelliamValidation). "team" est explicite ici (contrairement à
 *  MonthlyPlanningDecisionRequest) car Excelliam peut valider plusieurs équipes différentes,
 *  alors qu'un Team Leader ne décide toujours que pour la sienne. reason obligatoire si approve=false. */
public record ExcelliamPlanningDecisionRequest(String team, LocalDate from, LocalDate to, boolean approve, String reason) {
}
