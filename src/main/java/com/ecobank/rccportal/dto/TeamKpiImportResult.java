package com.ecobank.rccportal.dto;

import java.util.List;

public record TeamKpiImportResult(
        String team,
        int metricsProcessed,
        int valuesCreated,
        int targetsSkippedIntentionally,
        String importBatchId,
        int numericCellsDetected,
        List<SkippedValue> skippedValues
) {
    public record SkippedValue(String metricLabel, String columnLabel, String rawValue, String reason) {
    }
}
