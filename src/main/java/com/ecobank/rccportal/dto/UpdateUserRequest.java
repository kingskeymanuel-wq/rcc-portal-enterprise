package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** Mise à jour partielle — un champ null est laissé inchangé (sauf convention explicite ci-dessous). */
public record UpdateUserRequest(
        String username,
        String name,
        String email,
        String affiliateBranch,
        String gender,
        String contractType,
        String contractStatus,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        String activity,
        String residencePlace,
        String ledTeam
) {
}
