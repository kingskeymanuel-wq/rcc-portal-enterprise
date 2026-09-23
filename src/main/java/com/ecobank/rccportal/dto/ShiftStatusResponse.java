package com.ecobank.rccportal.dto;

/** currentState : WORKING | ON_PAUSE | ON_LUNCH | SHIFT_ENDED | NOT_STARTED */
public record ShiftStatusResponse(String currentState, java.util.List<ShiftEventResponse> todayEvents) {
}