package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentDossierRequest(
        String linkedUserMatricule,
        @NotBlank(message = "source is required.") String source,
        @NotBlank(message = "payload is required.") String payload) {
}
