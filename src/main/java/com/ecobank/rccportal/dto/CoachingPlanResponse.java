package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CoachingPlanResponse(
        Integer id, String agentMatricule, String agentName, String axis,
        LocalDate dueDate, String status, String note,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
