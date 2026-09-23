package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;

/** currentState : WORKING | ON_PAUSE | ON_LUNCH | ON_TRAINING | ON_MEETING | SHIFT_ENDED | NOT_STARTED */
public record LiveShiftStatusResponse(
        String username,
        String fullName,
        String team,
        String currentState,
        LocalDateTime since
) {
}
