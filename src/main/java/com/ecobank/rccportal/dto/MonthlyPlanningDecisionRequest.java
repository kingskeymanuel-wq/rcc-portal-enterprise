package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** Décision du Team Leader sur le planning en attente de son équipe (voir
 *  ScheduleService.decideMonthlyPlanning). reason obligatoire si approve=false. */
public record MonthlyPlanningDecisionRequest(LocalDate from, LocalDate to, boolean approve, String reason) {
}
