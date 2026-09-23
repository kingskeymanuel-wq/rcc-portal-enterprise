package com.ecobank.rccportal.dto;

import java.time.LocalDate;

/** Création d'une demande de permutation — targetUsername doit être un agent de LA MÊME
 *  équipe que le demandeur (vérifié côté service), jamais un choix libre inter-équipes. */
public record CreateShiftSwapRequest(
        LocalDate requesterDate,
        String targetUsername,
        LocalDate targetDate,
        String message
) {
}
