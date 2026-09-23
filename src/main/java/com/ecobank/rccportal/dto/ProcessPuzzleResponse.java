package com.ecobank.rccportal.dto;

import java.util.List;

public record ProcessPuzzleResponse(
        String procedureTitle,
        List<ProcessPuzzleStep> steps
) {
    public record ProcessPuzzleStep(Integer stepId, String text) {}
}
