package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Une entrée de planning pour un agent et un jour donné — code de shift brut (M, M2, OFF,
 * ABS, C...) tel qu'importé, avec son libellé humain et son horaire s'il y en a un (null pour
 * un jour sans prise de poste : OFF/ABS/RM/P/PU/Congés).
 */
public record AgentScheduleResponse(
        String username, String fullName, String team,
        LocalDate workDate, LocalTime startTime, LocalTime endTime,
        String shiftCode, String shiftLabel, boolean overnightCrossesMidnight,
        String approvalStatus, String rejectionReason, String origin) {
}
