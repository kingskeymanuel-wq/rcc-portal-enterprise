package com.ecobank.rccportal.dto;

import java.util.List;

public record SalesJourneyResponse(
        Integer journeyId,
        String journeyKey,
        String title,
        String icon,
        String colorFrom,
        String colorTo,
        String pitch,
        List<StepDto> steps,
        Integer sortOrder,
        Boolean active
) {
    public record StepDto(String title, List<String> content) {}
}
