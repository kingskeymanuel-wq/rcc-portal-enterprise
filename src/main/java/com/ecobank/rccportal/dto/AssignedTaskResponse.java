package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AssignedTaskResponse(
        Integer taskId, String title, String description,
        String assignedToUsername, String assignedToName, String assignedToTeamCode,
        String createdByUsername, String createdByName,
        LocalDate dueDate, String priority, String status, LocalDateTime createdAt,
        String category, Boolean justified, LocalDate relatedDate) {
}
