package com.ecobank.rccportal.dto;

import java.time.LocalDate;

public record QaCoachingRequest(
        String agentUsername,
        LocalDate relatedDate,
        String notes
) {
}
