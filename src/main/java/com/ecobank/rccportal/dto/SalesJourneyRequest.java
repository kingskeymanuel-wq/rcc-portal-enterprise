package com.ecobank.rccportal.dto;

import java.util.List;

public record SalesJourneyRequest(
        String journeyKey,
        String title,
        String icon,
        String colorFrom,
        String colorTo,
        String pitch,
        List<SalesJourneyResponse.StepDto> steps,
        Integer sortOrder,
        Boolean active
) {
}
