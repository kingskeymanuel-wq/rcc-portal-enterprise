package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record AgentDossierResponse(
        Integer id, String linkedUserMatricule, String linkedUserName, String source, String payload,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
