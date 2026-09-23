package com.ecobank.rccportal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ManualKpiEntryResponse(
        Integer id, String subjectMatricule, String subjectName, String enteredByMatricule,
        String metricCode, BigDecimal metricValue, LocalDate periodDate,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
