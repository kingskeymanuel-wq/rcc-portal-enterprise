package com.ecobank.rccportal.dto;

import java.math.BigDecimal;
import java.util.List;

public record TeamKpiSeriesResponse(
        String team,
        String metricCode,
        List<MonthlyPoint> points
) {
    public record MonthlyPoint(String month, BigDecimal value) {
    }
}
