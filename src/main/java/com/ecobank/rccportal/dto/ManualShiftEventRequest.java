package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

public record ManualShiftEventRequest(String username, String eventType, LocalDateTime occurredAt) {
}
