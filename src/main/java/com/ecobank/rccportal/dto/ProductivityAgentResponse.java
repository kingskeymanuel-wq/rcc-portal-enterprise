package com.ecobank.rccportal.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductivityAgentResponse(
        String username,
        String fullName,
        String metricCode,
        List<MonthlyValue> history
) {
    public record MonthlyValue(String month, BigDecimal value) {
    }
}
