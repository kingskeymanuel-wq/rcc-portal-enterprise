package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Ces contraintes ne s'appliquent qu'à la création (@Valid n'est posé que sur
 * ProcedureController.create) — update() reste une mise à jour partielle, champs optionnels.
 * serviceCode/countryCode optionnels — vide/absent = procédure générique visible par toutes les équipes/filiales. */
public record ProcedureRequest(
        @NotBlank(message = "zoneCode is required.") String zoneCode,
        String serviceCode,
        String countryCode,
        @NotBlank(message = "Title is required.") String title,
        String slaDelay,
        String level,
        String responsibleTeam,
        @NotEmpty(message = "At least one step is required.") List<String> steps) {
}
