package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Les contraintes ci-dessous ne s'appliquent qu'à la création (@Valid n'est posé que sur
 * CoachingPlanController.create) — update() reste une mise à jour partielle, champs optionnels. */
public record CoachingPlanRequest(
        @NotBlank(message = "agentMatricule is required.") String agentMatricule,
        @NotBlank(message = "axis is required.") String axis,
        @NotNull(message = "dueDate is required.") LocalDate dueDate,
        String status,
        String note) {
}
