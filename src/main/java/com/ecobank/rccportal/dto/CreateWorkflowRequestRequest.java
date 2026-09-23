package com.ecobank.rccportal.dto;

import java.time.LocalDate;

public record CreateWorkflowRequestRequest(
        String type,
        String title,
        String details,
        String periodType,
        LocalDate periodFrom,
        LocalDate periodTo,
        String assignedTeam,
        String assignedToUsername,
        String serviceCode,
        String priority
) {
}
