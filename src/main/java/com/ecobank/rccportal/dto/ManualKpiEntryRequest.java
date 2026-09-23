package com.ecobank.rccportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManualKpiEntryRequest(
        @NotBlank(message = "subjectMatricule is required.") String subjectMatricule,
        @NotBlank(message = "metricCode is required.") String metricCode,
        @NotNull(message = "metricValue is required.")
        @PositiveOrZero(message = "metricValue must be a non-negative number.") BigDecimal metricValue,
        @NotNull(message = "periodDate is required.") LocalDate periodDate) {
}
