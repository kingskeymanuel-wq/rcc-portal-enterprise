package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SlaRuleRequest(
        @NotBlank String motif,
        @NotBlank String category,
        String level,
        @NotNull Integer slaHours,
        @NotBlank String slaLabel,
        String destinationService,
        String priority,
        Boolean autoEscalation,
        String notes,
        Boolean isActive,
        Integer sortOrder,
        Integer poleId
) {
}
