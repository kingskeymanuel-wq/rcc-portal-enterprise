package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Ces contraintes ne s'appliquent qu'à la création (@Valid n'est posé que sur
 * MailTemplateController.create) — update() reste une mise à jour partielle, re-validée
 * après fusion côté service (MailTemplateService.update). */
public record MailTemplateRequest(
        @NotNull(message = "categoryId is required.") Integer categoryId,
        @NotBlank(message = "Subject is required.") String subject,
        @NotBlank(message = "Body is required.") String body,
        @NotBlank(message = "recipientType is required.") String recipientType,
        Integer recipientGroupId) {
}
