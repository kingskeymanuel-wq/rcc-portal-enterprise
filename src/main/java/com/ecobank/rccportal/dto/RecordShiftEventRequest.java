package com.ecobank.rccportal.dto;

/** eventType : PAUSE_START | PAUSE_END | LUNCH_START | LUNCH_END | SHIFT_END (LOGIN reste automatique, voir AuthService). */
public record RecordShiftEventRequest(String eventType) {
}