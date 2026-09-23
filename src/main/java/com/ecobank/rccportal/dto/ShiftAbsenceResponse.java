package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/** Absence pour déconnexion dans la journée — reconnectedAt null = toujours déconnecté. */
public record ShiftAbsenceResponse(LocalDateTime disconnectedAt, LocalDateTime reconnectedAt, long minutes) {
}
