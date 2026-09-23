package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record KpiEventResponse(Integer id, String matricule, String eventType, String eventKey, LocalDateTime occurredAt) {
}
