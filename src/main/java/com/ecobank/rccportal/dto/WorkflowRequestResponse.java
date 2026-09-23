package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WorkflowRequestResponse(
        Integer requestId,
        String type,
        String title,
        String details,
        String periodType,
        LocalDate periodFrom,
        LocalDate periodTo,
        String assignedTeam,
        String assignedToUsername,
        String assignedToName,
        String status,
        String requestedByUsername,
        String requestedByName,
        String requestedByAffiliateBranch,
        String requestedByService,
        String requestedByActivity,
        String relatedServiceCode,
        String relatedServiceName,
        LocalDateTime createdAt,
        String decidedByUsername,
        String decisionComment,
        LocalDateTime decidedAt,
        Long hoursOpen,
        Boolean slaBreached,
        String priority,
        LocalDateTime acknowledgedAt,
        String acknowledgedBy,
        String acknowledgementNote,
        LocalDateTime escalatedAt,
        LocalDateTime escalationDueAt,
        String decidedByName
) {
}
